-- Sluice baseline schema.
--
-- Money is stored in MICROS: 1 unit of currency = 1_000_000 micros.
-- Minor units (cents) are too coarse for LLM billing -- a 300-token call on a
-- $3/MTok model costs $0.0009, which rounds to zero cents. Micros give six
-- decimal places, and a signed BIGINT still spans ~$9.2 trillion.

create table account (
    id          uuid        primary key,
    parent_id   uuid        references account (id),
    name        text        not null,
    type        text        not null check (type in ('ORG', 'TEAM', 'PROJECT')),
    currency    char(3)     not null,
    created_at  timestamptz not null default now()
);

create index account_parent_idx on account (parent_id);

-- An account may not be its own parent. Deeper cycles are prevented in the
-- application by walking the chain before insert.
alter table account add constraint account_not_self_parent check (parent_id is distinct from id);

create table virtual_key (
    id          uuid        primary key,
    account_id  uuid        not null references account (id),
    key_hash    text        not null unique,
    key_prefix  text        not null,
    name        text        not null,
    created_at  timestamptz not null default now(),
    revoked_at  timestamptz
);

create index virtual_key_account_idx on virtual_key (account_id);

create table budget (
    id            uuid        primary key,
    account_id    uuid        not null references account (id),
    period        text        not null check (period in ('DAILY', 'MONTHLY', 'TOTAL')),
    limit_micros  bigint      not null check (limit_micros >= 0),
    currency      char(3)     not null,
    hard_stop     boolean     not null default true,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    unique (account_id, period)
);

-- ---------------------------------------------------------------------------
-- Double-entry ledger
-- ---------------------------------------------------------------------------

create table journal_entry (
    id               uuid        primary key,
    idempotency_key  text        not null unique,
    type             text        not null check (type in
                                 ('DEPOSIT', 'HOLD', 'SETTLE', 'RELEASE', 'ADJUSTMENT')),
    fx_rate          numeric(24, 12) not null default 1 check (fx_rate > 0),
    metadata         jsonb       not null default '{}'::jsonb,
    created_at       timestamptz not null default now()
);

create index journal_entry_created_idx on journal_entry (created_at);

-- account_ref namespaces the ledger accounts described in the PRD:
--   credits:<account_id>  spendable prepaid pot        (normal DEBIT balance)
--   holds:<account_id>    reserved, not yet settled    (normal DEBIT balance)
--   usage:<account_id>    lifetime consumption         (normal DEBIT balance)
--   equity:funding        contra account money enters through
--
-- Balance is uniformly defined as SUM(debits) - SUM(credits), so the whole
-- ledger sums to exactly zero at all times.
create table posting (
    id             bigserial   primary key,
    entry_id       uuid        not null references journal_entry (id),
    account_ref    text        not null,
    account_id     uuid        references account (id),
    amount_micros  bigint      not null check (amount_micros > 0),
    currency       char(3)     not null,
    direction      text        not null check (direction in ('DEBIT', 'CREDIT'))
);

create index posting_entry_idx on posting (entry_id);
create index posting_ref_idx on posting (account_ref);
create index posting_account_idx on posting (account_id);

-- THE invariant. Deferred to commit time so a multi-row insert is legal
-- mid-transaction but can never be committed unbalanced -- under any
-- concurrency, at any point.
create or replace function assert_entry_balanced() returns trigger as $$
declare
    target uuid := coalesce(new.entry_id, old.entry_id);
    offending record;
begin
    select currency,
           sum(case when direction = 'DEBIT' then amount_micros else 0 end)  as debits,
           sum(case when direction = 'CREDIT' then amount_micros else 0 end) as credits
      into offending
      from posting
     where entry_id = target
     group by currency
    having sum(case when direction = 'DEBIT' then amount_micros else 0 end)
        <> sum(case when direction = 'CREDIT' then amount_micros else 0 end)
     limit 1;

    if found then
        raise exception
            'journal entry % is unbalanced in %: debits=% credits=%',
            target, offending.currency, offending.debits, offending.credits
            using errcode = 'check_violation';
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger posting_balanced
    after insert or update or delete on posting
    deferrable initially deferred
    for each row execute function assert_entry_balanced();

-- ---------------------------------------------------------------------------
-- Holds, usage, rates
-- ---------------------------------------------------------------------------

create table hold (
    id             uuid        primary key,
    entry_id       uuid        not null unique references journal_entry (id),
    account_id     uuid        not null references account (id),
    request_id     text        not null unique,
    amount_micros  bigint      not null check (amount_micros > 0),
    currency       char(3)     not null,
    status         text        not null check (status in ('OPEN', 'SETTLED', 'RELEASED')),
    created_at     timestamptz not null default now(),
    expires_at     timestamptz not null,
    resolved_at    timestamptz
);

-- The sweeper's working index: find OPEN holds past their expiry.
create index hold_open_expiry_idx on hold (expires_at) where status = 'OPEN';
create index hold_account_idx on hold (account_id);

create table usage_record (
    id                           uuid        primary key,
    entry_id                     uuid        not null references journal_entry (id),
    account_id                   uuid        not null references account (id),
    request_id                   text        not null unique,
    model                        text        not null,
    input_tokens                 bigint      not null default 0,
    output_tokens                bigint      not null default 0,
    cache_creation_input_tokens  bigint      not null default 0,
    cache_read_input_tokens      bigint      not null default 0,
    cost_micros                  bigint      not null,
    currency                     char(3)     not null,
    streamed                     boolean     not null default false,
    partial                      boolean     not null default false,
    latency_ms                   integer     not null default 0,
    created_at                   timestamptz not null default now()
);

create index usage_account_created_idx on usage_record (account_id, created_at);

create table model_rate (
    model                        text            not null,
    input_per_mtok_micros        bigint          not null check (input_per_mtok_micros >= 0),
    output_per_mtok_micros       bigint          not null check (output_per_mtok_micros >= 0),
    cache_write_per_mtok_micros  bigint          not null check (cache_write_per_mtok_micros >= 0),
    cache_read_per_mtok_micros   bigint          not null check (cache_read_per_mtok_micros >= 0),
    currency                     char(3)         not null default 'USD',
    effective_from               timestamptz     not null default timestamptz '1970-01-01 00:00:00Z',
    primary key (model, effective_from)
);
