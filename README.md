# Port forwarder project
A NIO based, simple and scalable port forwarding written in Kotlin. 
It works like nginx. A single thread can handle thousands of concurrent connections.

The program allows you to forward ports from the the host running this program to a remote host.

Why would you want to do that?

Assume you have a host that has access to all resources, but not your machine.
You can run SSH based port forwarding on adhoc basis, you can also run this program to help you.

This is very efficient program based on NIO.

It runs a single thread, no matter how many connections.

It uses only 1MB for buffer, no matter how many connections.

It almost never allocate objects except for the SocketChannels, so it would not run GC (almost).
# Requirement
```shell
Gradle 6.x
Java 1.8+
Windows, Mac, Linux(all supported)
```
# Building

```shell
$ gradle jar
```
You will get the output in `build/libs/portforwarder-1.0.jar`.

# Running
```shell
$ java -jar build/libs/portforwarder-1.0.jar \
  localhost:2222::remote.host.com:22 \
  0.0.0.0:1443::www.google.com:443
```
The above command will cause:

Connection to localhost port 2222 will be effectively connecting to remote.host.com port 22.

Connection to localmachine (any IP) port 1443 will be effectively connecting to www.google.com port 443

NOTE:

the source and target ports are separated by `::` (not single `:`)

# Other supported parameters
```shell
You can add jvm parameters in the "-Dxxx.xx=xxx" way to the program.

Supported params:
ParameterName               default value
buffer.size                 1048576 (1MiB)
stats.interval              30000 (30 seconds)
enable.timestamp.in.log     true (enable timestamp in log or not)
log.level                   0 (0 for everything, 1 for info+, 2 for warn+, 3 for error+, 4 for nothing)
```
# Trying it out
```shell
$ ssh -p 2222 user@localhost
user@localhost's password:
Linux chairman 4.19.0-10-amd64 #1 SMP Debian 4.19.131-2 (2020-07-11) x86_64

Welcome to Alibaba Cloud Elastic Compute Service !

Last login: Fri Jul 23 16:28:04 2021 from [reducted]
Welcome to fish, the friendly interactive shell
user@chairman ~> ^D

```
# Checking
## Take a look at java logs, what do you see?
In general there should be logs for each connection, disconnection, bytes transferred.

## Try multiple windows concurrently, does it work?
Multiple sessions can be opened at the same time, since it is async NIO enabled.

## Try SCP over the forwarded `ssh` port, does it run `as fast`?
Based on my test, the SCP over forwarded port works equally well.
It utilizes the zero copy technology.

# Running as service in Linux (systemd flavor)
```shell
# Sample systemd file
[Unit]
Description=The port forwarder by Wu Shilin
After=network.target

[Service]
Type=simple
User=wushilin
Group=wushilin
ExecStart=/usr/bin/java -Denable.timestamp.in.log=false -jar \
  /opt/portforwarder-1.0.jar \
  localhost:2222::remote.host.com:22 \
  0.0.0.0:9443::www.google.com:443
Restart=always
RestartSec=3
SyslogIdentifier=portforwarder

[Install]
WantedBy=multi-user.target
```
Please change User, Group, and ExecStart so they make sense.

If listening to priviledged port (<1024), it must be run as `root`
