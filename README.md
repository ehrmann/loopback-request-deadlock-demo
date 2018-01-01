# Loopack Request Deadlock Demo

This demonstrates an antipattern I've seen in webservices.

When a request or response is intercepted and triggers a second request to the
same service (like to forward a request or decorate a response), deadlocks can
occur when the service nears capacity. Rather than failing gracefully and
fulfilling requests up to the service's capacity, the service deadlocks until
the request rate falls.

The cause is that while the second request is waiting in the request queue,
the original request is still using a worker thread. Below capacity, this is
fine, but at capacity, the waiting threads exhaust the worker thread pool,
their delegate requests never complete, and new requests rarely get enqueued.

There are two solutions to this problem: use a shorter queue or use separate
worker pools for each request class. Tuning the queue length is brittle and
impractical because it depends on the number of worker threads and
processing time. The simpler solution is to send loopback requests to a
separate queue. This usually implies a separate worker pool and port, as well.

Below is the output of the demo. The first run uses a shared queue. Once the
service nears capacity, it deadlocks and stops servicing requests. The second
run uses a shorter work queue. When the service hits capacity, it errs on
excessive requests, but otherwise keeps running. The third run uses separate
queues and thread pools.

```
Testing with a single thread pool and a large queue
rate: 30, success: 11, errors: 0
rate: 40, success: 32, errors: 0
rate: 50, success: 49, errors: 0
rate: 60, success: 60, errors: 0
rate: 70, success: 67, errors: 0
rate: 80, success: 79, errors: 0
rate: 90, success: 88, errors: 0
rate: 100, success: 104, errors: 0
rate: 110, success: 109, errors: 0
rate: 120, success: 118, errors: 0
rate: 130, success: 135, errors: 0
rate: 140, success: 133, errors: 0
rate: 150, success: 130, errors: 0
rate: 160, success: 128, errors: 0
rate: 170, success: 55, errors: 0
rate: 180, success: 7, errors: 52
rate: 190, success: 0, errors: 59
rate: 200, success: 0, errors: 64
rate: 210, success: 0, errors: 64
rate: 220, success: 0, errors: 244
rate: 230, success: 0, errors: 299
rate: 240, success: 0, errors: 279
rate: 250, success: 0, errors: 351
rate: 260, success: 0, errors: 353
rate: 270, success: 0, errors: 337
Throughput stable at 1 rps; quitting

Testing with a single thread pool and a small queue
rate: 30, success: 22, errors: 0
rate: 40, success: 38, errors: 0
rate: 50, success: 50, errors: 0
rate: 60, success: 59, errors: 0
rate: 70, success: 68, errors: 0
rate: 80, success: 79, errors: 0
rate: 90, success: 88, errors: 0
rate: 100, success: 103, errors: 0
rate: 110, success: 111, errors: 0
rate: 120, success: 110, errors: 0
rate: 130, success: 135, errors: 0
rate: 140, success: 141, errors: 0
rate: 150, success: 129, errors: 0
rate: 160, success: 84, errors: 67
rate: 170, success: 71, errors: 120
rate: 180, success: 73, errors: 124
rate: 190, success: 72, errors: 118
rate: 200, success: 74, errors: 149
rate: 210, success: 72, errors: 169
rate: 220, success: 78, errors: 154
rate: 230, success: 68, errors: 163
rate: 240, success: 72, errors: 149
rate: 250, success: 80, errors: 239
Throughput stable at 74 rps; quitting

Testing with two thread pools and a large queue
rate: 30, success: 22, errors: 0
rate: 40, success: 35, errors: 0
rate: 50, success: 49, errors: 0
rate: 60, success: 60, errors: 0
rate: 70, success: 68, errors: 0
rate: 80, success: 80, errors: 0
rate: 90, success: 87, errors: 0
rate: 100, success: 103, errors: 0
rate: 110, success: 107, errors: 0
rate: 120, success: 107, errors: 0
rate: 130, success: 106, errors: 0
rate: 140, success: 108, errors: 0
rate: 150, success: 106, errors: 0
rate: 160, success: 106, errors: 0
rate: 170, success: 107, errors: 0
rate: 180, success: 107, errors: 0
rate: 190, success: 106, errors: 0
rate: 200, success: 107, errors: 92
rate: 210, success: 106, errors: 125
rate: 220, success: 106, errors: 130
rate: 230, success: 103, errors: 131
rate: 240, success: 111, errors: 129
rate: 250, success: 106, errors: 198
rate: 260, success: 103, errors: 211
rate: 270, success: 107, errors: 214
rate: 280, success: 108, errors: 199
rate: 290, success: 105, errors: 161
rate: 300, success: 97, errors: 177
rate: 310, success: 110, errors: 200
rate: 320, success: 113, errors: 201
rate: 330, success: 97, errors: 291
rate: 340, success: 110, errors: 345
rate: 350, success: 113, errors: 332
rate: 360, success: 97, errors: 328
rate: 370, success: 110, errors: 325
rate: 380, success: 110, errors: 320
Throughput stable at 104 rps; quitting
```