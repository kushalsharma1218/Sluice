-- Published Anthropic first-party list prices, in micros per million tokens
-- ($5.00/MTok == 5_000_000 micros/MTok).
--
-- Cache write/read are seeded at the standard multipliers (1.25x input for a
-- cache write, 0.10x input for a cache read) except where a model publishes its
-- own rate. Rates are versioned by effective_from: never UPDATE a row, INSERT a
-- new one with a later effective_from so historical usage stays reproducible.

insert into model_rate
    (model, input_per_mtok_micros, output_per_mtok_micros,
     cache_write_per_mtok_micros, cache_read_per_mtok_micros, currency)
values
    ('claude-fable-5-1',  10000000, 50000000, 12500000,   250000, 'USD'),
    ('claude-mythos-5-1', 10000000, 50000000, 12500000,  1000000, 'USD'),
    ('claude-fable-5',    10000000, 50000000, 12500000,  1000000, 'USD'),
    ('claude-opus-5',      5000000, 25000000,  6250000,   500000, 'USD'),
    ('claude-opus-4-8',    5000000, 25000000,  6250000,   500000, 'USD'),
    ('claude-opus-4-7',    5000000, 25000000,  6250000,   500000, 'USD'),
    ('claude-opus-4-6',    5000000, 25000000,  6250000,   500000, 'USD'),
    ('claude-sonnet-5',    2000000, 10000000,  2500000,   200000, 'USD'),
    ('claude-sonnet-4-6',  3000000, 15000000,  3750000,   300000, 'USD'),
    ('claude-haiku-4-5',   1000000,  5000000,  1250000,   100000, 'USD');
