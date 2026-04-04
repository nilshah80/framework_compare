wrk.method = "PUT"
wrk.headers["Content-Type"] = "application/json"
wrk.headers["X-Api-Key"] = "bench-token"
wrk.body = '{"name":"John Doe","email":"john@example.com","phone":"+1-555-0100","address":{"street":"123 Main St","city":"Springfield","state":"IL","zip":"62701","country":"US"},"preferences":{"language":"en","currency":"USD","timezone":"America/Chicago","notifications":{"email":true,"sms":false,"push":true},"theme":"dark"},"payment_methods":[{"type":"visa","last4":"4242","expiry_month":12,"expiry_year":2027,"is_default":true},{"type":"mastercard","last4":"5555","expiry_month":6,"expiry_year":2026,"is_default":false}],"tags":["premium","early_adopter","beta_tester"],"metadata":{"source":"web","campaign":"summer2024","referrer":"google","signup_version":"2.1"}}'
