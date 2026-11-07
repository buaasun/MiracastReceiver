# Native PBC diagnostic (test package only)

This experimental helper is off by default. It runs only in a debug build whose
application ID is exactly `com.weekd.miracastreceiver.miracasttest`, after the
private file `files/wfd-auto-pbc.enable` has been created for that package.
Remove the file and restart the service to disable it. Root is required.

The helper observes only fresh supplicant PBC requests from
`ca:94:02:7d:c2:71`, verifies the current GO interface and socket, and sends
`WPS_PBC p2p_dev_addr=ca:94:02:7d:c2:71`; it never falls back to unqualified
`WPS_PBC`. Cancellation is attempted within five seconds only after an
unambiguous successful response. After FAIL, timeout or interruption, the
result is logged and cancellation is not sent; an ambiguous result may mean
that WPS is still active. It does not click the system UI.

The framework group is queried immediately before starting; connection broadcasts
refresh the group, and its removal or identity change invalidates the worker.
A framework notification may arrive after the group actually changes. Stopping destroys known Java process handles;
it cannot guarantee a vendor `su` implementation has killed all descendants,
or that a delayed root authorization will not start a child later. The shell
timeout is only effective after `su` starts the shell. Cancellation can itself
fail or time out. These limits prevent claiming a guaranteed safe stop or a
strictly bounded WPS window.

**An `OK` response does not establish that this device's vendor supplicant
actually enforces the peer restriction.** It also does not mean connection
succeeded. This command bypasses the Android framework acceptance state machine,
so the framework state and dialog can remain out of sync. Previous testing saw
a P2P response with no ACK before the WPS phase; this helper does not fix that.
Use only for a controlled test and inspect both cancellation and connection logs.
