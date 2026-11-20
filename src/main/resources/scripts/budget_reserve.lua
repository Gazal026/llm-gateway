-- Atomically reserve tokens from a tenant's daily budget.
-- KEYS[1]  budget counter key (one per tenant per UTC day)
-- ARGV[1]  daily limit
-- ARGV[2]  tokens to reserve
-- ARGV[3]  key TTL in seconds
-- Returns  { allowed (1|0), tokens used after this call }

local limit = tonumber(ARGV[1])
local amount = tonumber(ARGV[2])
local used = tonumber(redis.call('GET', KEYS[1]) or '0')

if used + amount > limit then
  return { 0, used }
end

used = redis.call('INCRBY', KEYS[1], amount)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return { 1, used }
