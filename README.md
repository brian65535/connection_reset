# connection_reset
Unit test to reproduce the case that HttpClientRequest.reset() does NOT properly reset the client connection in Vert.x 3.4.1

# Update
 - The issue is fixed in Vert.x 3.5.0

# Unit Test

```
$ mvn clean test
```

Results
```
Running ResetTest
Configuring TestNG with: org.apache.maven.surefire.testng.conf.TestNG652Configurator@6f2b958e
Begin test suite
Begin test Normal
[vert.x-eventloop-thread-0] INFO ResetTest - DNS server started
[vert.x-eventloop-thread-4] INFO ResetTest - Web server started
[vert.x-eventloop-thread-5] INFO ResetTest - Client ready
[vert.x-eventloop-thread-5] INFO ResetTest - ==========
[vert.x-eventloop-thread-5] INFO ResetTest - Making 1st client request to non.exist.com.us:4080
[vert.x-eventloop-thread-3] INFO ResetTest - 1: Client queueMap size: 0, connectionMap size: 0
[vert.x-eventloop-thread-0] INFO ResetTest - DNS question: non.exist.com.us
[vert.x-eventloop-thread-0] INFO ResetTest - DNS answer: 127.0.0.1
[vert.x-eventloop-thread-0] INFO ResetTest - DNS question: non.exist.com.us
[vert.x-eventloop-thread-0] INFO ResetTest - DNS answer: 127.0.0.1
[vert.x-eventloop-thread-4] INFO ResetTest - web server got request with local addr: 127.0.0.1
[vert.x-eventloop-thread-3] INFO ResetTest - 1: request timeout
[vert.x-eventloop-thread-3] INFO ResetTest - 1: request reset succeeded
[vert.x-eventloop-thread-5] INFO ResetTest - ==========
[vert.x-eventloop-thread-5] INFO ResetTest - Sleep 5 seconds
[vert.x-eventloop-thread-5] INFO ResetTest - ==========
[vert.x-eventloop-thread-5] INFO ResetTest - Switched DNS answer, now answer is 192.168.0.1
[vert.x-eventloop-thread-5] INFO ResetTest - ==========
[vert.x-eventloop-thread-5] INFO ResetTest - Making 2nd client request to non.exist.com.us
[vert.x-eventloop-thread-5] INFO ResetTest - Making 3rd client request to non.exist.com.us
[vert.x-eventloop-thread-3] INFO ResetTest - 2: Client queueMap size: 1, connectionMap size: 1
[vert.x-eventloop-thread-3] INFO ResetTest - 3: Client queueMap size: 1, connectionMap size: 1
[vert.x-eventloop-thread-0] INFO ResetTest - DNS question: non.exist.com.us
[vert.x-eventloop-thread-0] INFO ResetTest - DNS answer: 192.168.0.1
[vert.x-eventloop-thread-4] INFO ResetTest - web server got request with local addr: 127.0.0.1
[vert.x-eventloop-thread-0] INFO ResetTest - DNS question: non.exist.com.us
[vert.x-eventloop-thread-0] INFO ResetTest - DNS answer: 192.168.0.1
[vert.x-eventloop-thread-4] INFO ResetTest - web server got request with local addr: 192.168.0.1
[vert.x-eventloop-thread-3] INFO ResetTest - 3: client got response, status: 200
[vert.x-eventloop-thread-3] INFO ResetTest - 3: response body: Hello!
[vert.x-eventloop-thread-3] INFO ResetTest - 2: request timeout
[vert.x-eventloop-thread-3] INFO ResetTest - 2: request reset succeeded
[vert.x-eventloop-thread-5] INFO ResetTest - ==========
[vert.x-eventloop-thread-5] INFO ResetTest - DONE
Passed Normal
End test suite  , run: 1, Failures: 0, Errors: 0
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.028 sec

Results :

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

web server is expected to get requests with local addr 192.168.0.1 for both request 2 and 3
