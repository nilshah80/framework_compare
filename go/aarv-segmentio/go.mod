module benchmark/aarv-segmentio

go 1.26

require (
	github.com/nilshah80/aarv v0.4.1-0.20260330161736-73e7917a5b72
	github.com/nilshah80/aarv/codec/segmentio v0.0.0
)

require (
	github.com/segmentio/asm v1.1.3 // indirect
	github.com/segmentio/encoding v0.4.1 // indirect
	golang.org/x/sys v0.0.0-20211110154304-99a53858aa08 // indirect
)

// TODO: remove replace once codec/segmentio/vX.Y.Z tag is pushed to github.com/nilshah80/aarv
replace github.com/nilshah80/aarv/codec/segmentio => github.com/nilshah80/aarv/codec/segmentio v0.0.0-20260322080848-a0589d474e94
