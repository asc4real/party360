package com.lms.party360.idem;

final class LuaScripts {
    // KEYS[1] = key
    // ARGV[1] = base64 hash
    // ARGV[2] = pending ttl seconds
    static final String CREATE_OR_VALIDATE = """
  local k     = KEYS[1]
  local hash  = ARGV[1]
  local pttl  = tonumber(ARGV[2])

  if redis.call('EXISTS', k) == 0 then
    redis.call('HSET', k, 'hash', hash, 'status', 'PENDING', 'ts', tostring(redis.call('TIME')[1]))
    redis.call('EXPIRE', k, pttl)
    return 'CREATED'
  end

  local oldHash = redis.call('HGET', k, 'hash')
  if oldHash ~= hash then
    return 'HASH_MISMATCH'
  end

  local status = redis.call('HGET', k, 'status')
  if status == 'DONE' then
    return 'DONE'
  else
    return 'PENDING'
  end
  """;

    // KEYS[1] = key
    // ARGV[1] = base64 hash
    // ARGV[2] = payload (binary via Redis driver)
    // ARGV[3] = ttl seconds (long)
    static final String COMPLETE_SUCCESS = """
  local k = KEYS[1]
  local hash = ARGV[1]

  if redis.call('EXISTS', k) == 0 then
    return 'MISSING'
  end

  local oldHash = redis.call('HGET', k, 'hash')
  if oldHash ~= hash then
    return 'HASH_MISMATCH'
  end

  redis.call('HSET', k, 'payload', ARGV[2], 'status', 'DONE', 'ts', tostring(redis.call('TIME')[1]))
  redis.call('EXPIRE', k, tonumber(ARGV[3]))
  return 'OK'
  """;

    // KEYS[1] = key
    // purpose: clean up after supplier failure so a retry can run
    static final String CLEAN_ON_FAILURE = """
  local k = KEYS[1]
  if redis.call('EXISTS', k) == 1 then
    redis.call('DEL', k)
  end
  return 'OK'
  """;
}

