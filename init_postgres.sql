CREATE USER postgres WITH PASSWORD 'example';
ALTER USER postgres WITH SUPERUSER;
CREATE DATABASE sandbox_builder;
GRANT ALL PRIVILEGES ON DATABASE sandbox_builder TO postgres;
