wrk.method = "PUT"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["X-Api-Key"] = "bench-token"
wrk.body = '{"items":[{"product_id":"prod_003","name":"Thingamajig","quantity":3,"price":19.99}],"currency":"EUR"}'
