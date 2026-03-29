wrk.method = "POST"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["X-Api-Key"] = "bench-token"
wrk.body = '{"items":[{"product_id":"prod_001","name":"Widget","quantity":2,"price":29.99},{"product_id":"prod_002","name":"Gadget","quantity":1,"price":49.99}],"currency":"USD"}'
