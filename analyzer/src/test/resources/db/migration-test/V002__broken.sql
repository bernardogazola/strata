CREATE TABLE migration_probe (x INTEGER) STRICT;
INSERT INTO migration_probe (x) VALUES (1);
CREATE TABLE migration_broken (x BOGUSTYPE) STRICT;
