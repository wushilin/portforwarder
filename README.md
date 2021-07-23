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

# Show me the numbers!
The following shows the original host, not via portforwarder performance.

`loadtest -n 10000 -c 100 -k --insecure https://<reducted>`
```shell
(node:37017) Warning: Setting the NODE_TLS_REJECT_UNAUTHORIZED environment variable to '0' makes TLS connections and HTTPS requests insecure by disabling certificate verification.
(Use `node --trace-warnings ...` to show where the warning was created)
[Fri Jul 23 2021 20:13:37 GMT+0800 (Singapore Standard Time)] INFO Requests: 0 (0%), requests per second: 0, mean latency: 0 ms
[Fri Jul 23 2021 20:13:42 GMT+0800 (Singapore Standard Time)] INFO Requests: 5999 (60%), requests per second: 1202, mean latency: 83.5 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Target URL:          https://<reducted>
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Max requests:        10000
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Concurrency level:   100
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Agent:               keepalive
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Completed requests:  10000
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Total errors:        0
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Total time:          7.6069384950000005 s
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Requests per second: 1315
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Mean latency:        75 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO Percentage of the requests served within a certain time
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO   50%      52 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO   90%      100 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO   95%      109 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO   99%      621 ms
[Fri Jul 23 2021 20:13:44 GMT+0800 (Singapore Standard Time)] INFO  100%      666 ms (longest request)
```

The following was run using nodejs loadtest with:

`loadtest -n 10000 -c 100 -k --insecure https://localhost`

```shell
(node:37026) Warning: Setting the NODE_TLS_REJECT_UNAUTHORIZED environment variable to '0' makes TLS connections and HTTPS requests insecure by disabling certificate verification.
(Use `node --trace-warnings ...` to show where the warning was created)
[Fri Jul 23 2021 20:13:58 GMT+0800 (Singapore Standard Time)] INFO Requests: 0 (0%), requests per second: 0, mean latency: 0 ms
[Fri Jul 23 2021 20:14:03 GMT+0800 (Singapore Standard Time)] INFO Requests: 5824 (58%), requests per second: 1167, mean latency: 86 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Target URL:          https://localhost
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Max requests:        10000
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Concurrency level:   100
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Agent:               keepalive
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Completed requests:  10000
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Total errors:        0
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Total time:          7.6591180240000005 s
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Requests per second: 1306
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Mean latency:        75.8 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO 
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO Percentage of the requests served within a certain time
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO   50%      54 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO   90%      99 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO   95%      142 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO   99%      601 ms
[Fri Jul 23 2021 20:14:06 GMT+0800 (Singapore Standard Time)] INFO  100%      666 ms (longest request)
```
As you can see, total time is almost identical. The request latency is almost the same!
This program almost has no overhead in general.
# Requirement
```shell
Gradle 6.x
Java 1.8+
Windows, Mac, Linux(all supported)
```
# Building

```shell
$ ./gradlew jar
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
