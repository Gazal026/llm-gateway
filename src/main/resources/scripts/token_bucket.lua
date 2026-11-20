-- Token-bucket rate limiter, executed atomically inside Redis so it is correct across many gateway instances.
-- KEYS[1]  bucket key (one per tenant)
-- ARGV[1]  capacity (max burst)
-- ARGV[2]  refill rate in tokens per second
-- ARGV[3]  tokens requested
-- Returns  { allowed (1|0), tokens remaining (floored), retry-after in ms }

local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

-- Use Redis' clock rather than each gateway instance's clock, so clock skew between instances can't skew limits.
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local last_ms = tonumber(state[2])
if tokens == nil or last_ms == nil then
  tokens = capacity
  last_ms = now_ms
end

local elapsed_s = math.max(0, now_ms - last_ms) / 1000
tokens = math.min(capacity, tokens + elapsed_s * rate)

local allowed = 0
local retry_after_ms = 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  retry_after_ms = math.ceil((requested - tokens) / rate * 1000)
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now_ms)
-- Expire idle buckets once they would have refilled completely anyway.
redis.call('PEXPIRE', KEYS[1], math.ceil(capacity / rate * 1000) + 1000)

return { allowed, math.floor(tokens), retry_after_ms }
