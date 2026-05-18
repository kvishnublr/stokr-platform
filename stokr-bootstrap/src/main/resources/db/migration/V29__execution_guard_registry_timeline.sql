create table if not exists execution_guard_policy_registry (
    id uuid primary key,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    deleted boolean not null default false,
    registry_key varchar(64) not null,
    display_name varchar(128) not null,
    active boolean not null default true,
    revision bigint not null default 1,
    notes varchar(512),
    unique (registry_key)
);

create table if not exists execution_guard_policy_override (
    id uuid primary key,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    deleted boolean not null default false,
    registry_id uuid references execution_guard_policy_registry(id),
    scope_type varchar(32) not null,
    scope_key varchar(128),
    guard_mode varchar(32),
    enabled boolean not null default true,
    max_signal_age_ms bigint,
    max_drift_pct numeric(12,6),
    max_spread_pct numeric(12,6),
    max_slippage_pct numeric(12,6),
    soft_stale_feed_ms bigint,
    hard_stale_feed_ms bigint,
    volatility_threshold_pct numeric(12,6),
    min_liquidity_qty numeric(24,8),
    allow_exit_during_stale_feed boolean,
    allow_soft_fail_execution boolean,
    metadata jsonb,
    revision bigint not null default 1
);

create index if not exists idx_guard_policy_override_scope
    on execution_guard_policy_override(scope_type, scope_key, guard_mode)
    where deleted = false;

create table if not exists execution_guard_policy_audit (
    id uuid primary key,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    deleted boolean not null default false,
    actor_user_id uuid,
    action varchar(32) not null,
    scope_type varchar(32),
    scope_key varchar(128),
    guard_mode varchar(32),
    before_payload jsonb,
    after_payload jsonb,
    notes varchar(512),
    revision bigint not null default 1
);

create index if not exists idx_guard_policy_audit_created
    on execution_guard_policy_audit(created_at desc)
    where deleted = false;

create table if not exists execution_timeline_events (
    id uuid primary key,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    version bigint not null default 0,
    deleted boolean not null default false,
    timeline_id uuid not null,
    correlation_id varchar(96),
    user_id uuid,
    signal_id uuid,
    strategy_key varchar(128),
    symbol varchar(64),
    event_type varchar(64) not null,
    event_time timestamptz not null,
    payload jsonb,
    latency_ms bigint,
    market_snapshot jsonb,
    guard_outcome varchar(32),
    severity varchar(16)
);

create index if not exists idx_execution_timeline_lookup
    on execution_timeline_events(timeline_id, event_time)
    where deleted = false;

create index if not exists idx_execution_timeline_signal
    on execution_timeline_events(signal_id, event_time desc)
    where deleted = false;

