CREATE TABLE meta (
	name VARCHAR(255) PRIMARY KEY,
	value VARCHAR(255)
);
INSERT INTO meta (name, value) VALUES ('data_version', '0');
CREATE TABLE releases (
	release_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
	user_id      BIGINT NOT NULL,
	title        VARCHAR(255) NOT NULL,
	subtitle     VARCHAR(255) NOT NULL,
	slug         VARCHAR(255) NOT NULL UNIQUE,
	published    BOOLEAN NOT NULL,
	art          VARCHAR(255),
	description  CLOB NOT NULL,
	created_at   TIMESTAMP NOT NULL,
	last_updated TIMESTAMP NOT NULL
);
CREATE TABLE tracks (
	track_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
	release_id  BIGINT NOT NULL,
	title       VARCHAR(255) NOT NULL,
	subtitle    VARCHAR(255) NOT NULL,
	slug        VARCHAR(255) NOT NULL UNIQUE,
	art         VARCHAR(255),
	master      VARCHAR(255) NOT NULL,
	description CLOB NOT NULL,
	created_at   TIMESTAMP NOT NULL,
	last_updated TIMESTAMP NOT NULL
);
CREATE TABLE transcodes (
	transcode_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
	track_id        BIGINT NOT NULL,
	master          VARCHAR(255),
	file            VARCHAR(255),
	created_at      TIMESTAMP NOT NULL,
	last_downloaded TIMESTAMP NOT NULL
);
CREATE TABLE users (
	user_id      BIGINT AUTO_INCREMENT PRIMARY KEY,
	username     VARCHAR(255) NOT NULL UNIQUE,
	display_name VARCHAR(255) NOT NULL,
	password     VARCHAR(255) NOT NULL,
	admin        BOOLEAN NOT NULL,
	created_at   TIMESTAMP NOT NULL,
	last_login   TIMESTAMP
);
CREATE TABLE sessions (
	session_id   UUID PRIMARY KEY,
	user_id      BIGINT NOT NULL,
	expires      TIMESTAMP NOT NULL
);

CREATE INDEX tracks_release_index
	ON tracks (release_id);
CREATE INDEX releases_user_index
	ON releases (user_id);
CREATE INDEX transcodes_track_index
	ON transcodes (track_id);

ALTER TABLE tracks ADD CONSTRAINT tracks_releases
	FOREIGN KEY (release_id) REFERENCES releases;
ALTER TABLE releases ADD CONSTRAINT releases_users
	FOREIGN KEY (user_id) REFERENCES users;
ALTER TABLE sessions ADD CONSTRAINT sessions_users
	FOREIGN KEY (user_id) REFERENCES users;