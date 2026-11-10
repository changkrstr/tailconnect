package tsbridge

/*
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <sys/socket.h>
*/
import "C"

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"time"
	"unsafe"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

func init() {
	netmon.RegisterInterfaceGetter(androidSafeInterfaces)
}

func androidSafeInterfaces() ([]netmon.Interface, error) {
	if ifs, err := net.Interfaces(); err == nil && len(ifs) > 0 {
		out := make([]netmon.Interface, len(ifs))
		for i := range ifs {
			out[i].Interface = &ifs[i]
			out[i].AltAddrs, _ = getifaddrsAddrs(ifs[i].Name)
		}
		return out, nil
	}
	return getifaddrsInterfaces()
}

type ifaceInfo struct {
	name  string
	index int
	flags net.Flags
	mtu   int
	addrs []net.Addr
}

func getifaddrsInterfaces() ([]netmon.Interface, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)

	byName := map[string]*ifaceInfo{}
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		name := C.GoString(ifa.ifa_name)
		info := byName[name]
		if info == nil {
			info = &ifaceInfo{
				name:  name,
				flags: translateFlags(uint32(ifa.ifa_flags)),
			}
			byName[name] = info
		}
		if addr := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); addr != nil {
			info.addrs = append(info.addrs, addr)
		}
	}

	out := make([]netmon.Interface, 0, len(byName))
	for _, info := range byName {
		ni := &net.Interface{
			Index: info.index,
			Name:  info.name,
			Flags: info.flags,
			MTU:   info.mtu,
		}
		out = append(out, netmon.Interface{
			Interface: ni,
			AltAddrs:  info.addrs,
		})
	}
	return out, nil
}

func getifaddrsAddrs(name string) ([]net.Addr, error) {
	var head *C.struct_ifaddrs
	if rc, err := C.getifaddrs(&head); rc != 0 {
		return nil, fmt.Errorf("getifaddrs: %w", err)
	}
	defer C.freeifaddrs(head)
	var addrs []net.Addr
	for ifa := head; ifa != nil; ifa = ifa.ifa_next {
		if C.GoString(ifa.ifa_name) != name {
			continue
		}
		if a := sockaddrToAddr(ifa.ifa_addr, ifa.ifa_netmask); a != nil {
			addrs = append(addrs, a)
		}
	}
	return addrs, nil
}

func translateFlags(f uint32) net.Flags {
	var out net.Flags
	if f&C.IFF_UP != 0 {
		out |= net.FlagUp
	}
	if f&C.IFF_BROADCAST != 0 {
		out |= net.FlagBroadcast
	}
	if f&C.IFF_LOOPBACK != 0 {
		out |= net.FlagLoopback
	}
	if f&C.IFF_POINTOPOINT != 0 {
		out |= net.FlagPointToPoint
	}
	if f&C.IFF_MULTICAST != 0 {
		out |= net.FlagMulticast
	}
	if f&C.IFF_RUNNING != 0 {
		out |= net.FlagRunning
	}
	return out
}

func sockaddrToAddr(sa, nm *C.struct_sockaddr) net.Addr {
	if sa == nil {
		return nil
	}
	switch sa.sa_family {
	case C.AF_INET:
		sin := (*C.struct_sockaddr_in)(unsafe.Pointer(sa))
		addr := (*[4]byte)(unsafe.Pointer(&sin.sin_addr))[:]
		ip := netip.AddrFrom4(*(*[4]byte)(addr))
		prefix := 32
		if nm != nil && nm.sa_family == C.AF_INET {
			mask := (*C.struct_sockaddr_in)(unsafe.Pointer(nm))
			maskBytes := (*[4]byte)(unsafe.Pointer(&mask.sin_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 32)}
	case C.AF_INET6:
		sin := (*C.struct_sockaddr_in6)(unsafe.Pointer(sa))
		addrBytes := (*[16]byte)(unsafe.Pointer(&sin.sin6_addr))[:]
		ip := netip.AddrFrom16(*(*[16]byte)(addrBytes))
		prefix := 128
		if nm != nil && nm.sa_family == C.AF_INET6 {
			mask := (*C.struct_sockaddr_in6)(unsafe.Pointer(nm))
			maskBytes := (*[16]byte)(unsafe.Pointer(&mask.sin6_addr))[:]
			prefix = countLeadingOnes(maskBytes)
		}
		return &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(prefix, 128)}
	}
	return nil
}

func countLeadingOnes(mask []byte) int {
	ones := 0
	for _, b := range mask {
		if b == 0xff {
			ones += 8
			continue
		}
		for b&0x80 != 0 {
			ones++
			b <<= 1
		}
		break
	}
	return ones
}

type TunnelHandle struct {
	srv    *tsnet.Server
	mu     sync.Mutex
	closed bool
}

type Conn struct {
	c net.Conn
}

func (c *Conn) RemoteAddrHost() string {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok && addr.IP != nil {
		return addr.IP.String()
	}
	return ""
}

func (c *Conn) RemoteAddrPort() int {
	if addr, ok := c.c.RemoteAddr().(*net.TCPAddr); ok {
		return addr.Port
	}
	return 0
}

func (c *Conn) Read(size int) ([]byte, error) {
	if size <= 0 {
		size = 4096
	}
	buf := make([]byte, size)
	n, err := c.c.Read(buf)
	if n > 0 {
		return buf[:n], err
	}
	if err == nil {
		err = io.EOF
	}
	return nil, err
}

func (c *Conn) Write(data []byte) error {
	_, err := c.c.Write(data)
	return err
}

func (c *Conn) Close() error {
	return c.c.Close()
}

type ListenerHandle struct {
	ln net.Listener
}

func (l *ListenerHandle) Accept() (*Conn, error) {
	c, err := l.ln.Accept()
	if err != nil {
		return nil, err
	}
	return &Conn{c: c}, nil
}

func (l *ListenerHandle) Port() int {
	if addr, ok := l.ln.Addr().(*net.TCPAddr); ok {
		return addr.Port
	}
	return 0
}

func (l *ListenerHandle) Close() error {
	return l.ln.Close()
}

type UDPConn struct {
	c net.PacketConn
}

type UDPRead struct {
	Data     []byte
	FromHost string
	FromPort int
}

func (u *UDPConn) ReadFrom(size int, timeoutMs int) (*UDPRead, error) {
	if size <= 0 {
		size = 2048
	}
	if timeoutMs > 0 {
		_ = u.c.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	} else {
		_ = u.c.SetReadDeadline(time.Time{})
	}
	buf := make([]byte, size)
	n, addr, err := u.c.ReadFrom(buf)
	if err != nil {
		return nil, err
	}
	udp, ok := addr.(*net.UDPAddr)
	if !ok {
		return &UDPRead{Data: buf[:n], FromHost: addr.String(), FromPort: 0}, nil
	}
	return &UDPRead{Data: buf[:n], FromHost: udp.IP.String(), FromPort: udp.Port}, nil
}

func (u *UDPConn) WriteTo(data []byte, host string, port int) error {
	ip := net.ParseIP(host)
	if ip == nil {
		return fmt.Errorf("WriteTo: host %q is not an IP literal", host)
	}
	addr := &net.UDPAddr{IP: ip, Port: port}
	_, err := u.c.WriteTo(data, addr)
	return err
}

func (u *UDPConn) Close() error {
	return u.c.Close()
}

func StartTunnel(authKey, stateDir, hostname, controlURL string) (th *TunnelHandle, retErr error) {
	defer func() {
		if r := recover(); r != nil {
			th = nil
			retErr = fmt.Errorf("tsnet start panic: %v", r)
		}
	}()

	if authKey == "" {
		return nil, errors.New("authkey required")
	}
	if stateDir == "" {
		return nil, errors.New("state directory required")
	}

	if err := os.MkdirAll(stateDir, 0700); err != nil {
		return nil, fmt.Errorf("mkdir stateDir: %w", err)
	}

	// Essential for Android untrusted_app:
	// /tmp does not exist on Android, causing logpolicy to panic with:
	// "no safe place found to store log state".
	// Pointing TS_LOGS_DIR, TMPDIR, HOME, and XDG_CACHE_HOME to the writable stateDir prevents this.
	os.Setenv("TS_LOGS_DIR", stateDir)
	os.Setenv("TMPDIR", stateDir)
	os.Setenv("HOME", stateDir)
	os.Setenv("XDG_CACHE_HOME", stateDir)

	if hostname == "" {
		hostname = "tailconnect-android"
	}
	srv := &tsnet.Server{
		AuthKey:    authKey,
		Dir:        stateDir,
		Hostname:   hostname,
		ControlURL: controlURL,
		Ephemeral:  false,
		Logf:       tsnetLogf,
		UserLogf:   tsnetLogf,
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if _, err := srv.Up(ctx); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet.Up: %w", err)
	}

	if err := waitForPeerMap(ctx, srv); err != nil {
		_ = srv.Close()
		return nil, fmt.Errorf("tsnet wait-for-peers: %w", err)
	}

	return &TunnelHandle{srv: srv}, nil
}

func tsnetLogf(format string, args ...any) {
	fmt.Printf("[tsnet] "+format+"\n", args...)
}

func waitForPeerMap(ctx context.Context, srv *tsnet.Server) error {
	lc, err := srv.LocalClient()
	if err != nil {
		return fmt.Errorf("LocalClient: %w", err)
	}
	var lastErr error
	for {
		st, err := lc.Status(ctx)
		if err == nil && st.Self != nil && (st.Self.Online || len(st.Peer) > 0) {
			return nil
		}
		lastErr = err
		select {
		case <-ctx.Done():
			return fmt.Errorf("timeout waiting for tailnet (last status err=%v)", lastErr)
		case <-time.After(500 * time.Millisecond):
		}
	}
}

func (t *TunnelHandle) Dial(host string, port int, timeoutMs int) (conn *Conn, retErr error) {
	defer func() {
		if r := recover(); r != nil {
			conn = nil
			retErr = fmt.Errorf("tsnet dial panic: %v", r)
		}
	}()

	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	if timeoutMs <= 0 {
		timeoutMs = 30000
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	target := net.JoinHostPort(host, strconv.Itoa(port))
	c, err := srv.Dial(ctx, "tcp", target)
	if err != nil {
		return nil, fmt.Errorf("dial %s: %w", target, err)
	}
	return &Conn{c: c}, nil
}

func (t *TunnelHandle) ListenTCP(port int) (handle *ListenerHandle, retErr error) {
	defer func() {
		if r := recover(); r != nil {
			handle = nil
			retErr = fmt.Errorf("tsnet listen tcp panic: %v", r)
		}
	}()

	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	addr := fmt.Sprintf(":%d", port)
	ln, err := srv.Listen("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("tsnet Listen %s: %w", addr, err)
	}
	return &ListenerHandle{ln: ln}, nil
}

func (t *TunnelHandle) ListenUDP(port int) (conn *UDPConn, retErr error) {
	defer func() {
		if r := recover(); r != nil {
			conn = nil
			retErr = fmt.Errorf("tsnet listen udp panic: %v", r)
		}
	}()

	t.mu.Lock()
	if t.closed {
		t.mu.Unlock()
		return nil, errors.New("tunnel closed")
	}
	srv := t.srv
	t.mu.Unlock()

	ip4, ip6 := srv.TailscaleIPs()
	var bind string
	switch {
	case ip4.IsValid():
		bind = net.JoinHostPort(ip4.String(), strconv.Itoa(port))
	case ip6.IsValid():
		bind = net.JoinHostPort(ip6.String(), strconv.Itoa(port))
	default:
		return nil, errors.New("tailnet has not assigned a usable IP yet")
	}
	pc, err := srv.ListenPacket("udp", bind)
	if err != nil {
		return nil, fmt.Errorf("tsnet ListenPacket %s: %w", bind, err)
	}
	return &UDPConn{c: pc}, nil
}

func (t *TunnelHandle) LocalIPv4() string {
	defer func() {
		_ = recover()
	}()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed || t.srv == nil {
		return ""
	}
	ip4, _ := t.srv.TailscaleIPs()
	if ip4.IsValid() {
		return ip4.String()
	}
	return ""
}

func (t *TunnelHandle) Status() string {
	defer func() {
		_ = recover()
	}()
	t.mu.Lock()
	if t.closed || t.srv == nil {
		t.mu.Unlock()
		return "stopped"
	}
	srv := t.srv
	t.mu.Unlock()

	lc, err := srv.LocalClient()
	if err != nil {
		return "unknown"
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	st, err := lc.Status(ctx)
	if err != nil {
		return "connecting"
	}
	if st.Self != nil && st.Self.Online {
		return "online"
	}
	return "starting"
}

func (t *TunnelHandle) Close() {
	defer func() {
		_ = recover()
	}()
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.closed {
		return
	}
	t.closed = true
	if t.srv != nil {
		_ = t.srv.Close()
		t.srv = nil
	}
}
