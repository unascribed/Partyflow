CREATE TABLE `meta` (
	`name` VARCHAR(255) PRIMARY KEY,
	`value` CLOB NOT NULL
);
INSERT INTO `meta` (`name`, `value`) VALUES ('data_version', '0');
INSERT INTO `meta` (`name`, `value`) VALUES ('site_description', '
<h1>Welcome to Partyflow!</h1>
<p>
	Partyflow is a self-hostable media transcoding service and release manager, styled after
	Bandcamp. It&apos;s free and open source. If you&apos;re reading this, congratulations! Your
	Partyflow installation is working.
</p>
<p>
	Partyflow handles the nitty-gritty of transcoding audio files for you with a fast glossy
	JavaScript-optional interface, and only encodes media to a given format when someone
	requests it for the first time. It will even delete media transcodes that haven&apos;t been
	downloaded in a while to save storage. (This actively wastes money on Wasabi, so, there
	is a config flag to turn this off if you&apos;re using Wasabi.)
</p>
<p>
	You can change what this homepage says in the admin panel.
</p>
');

CREATE TABLE `releases` (
	`release_id`    BIGINT AUTO_INCREMENT PRIMARY KEY,
	`user_id`       BIGINT NOT NULL,
	`title`         VARCHAR(255) NOT NULL,
	`subtitle`      VARCHAR(255) NOT NULL,
	`slug`          VARCHAR(255) NOT NULL UNIQUE,
	`published`     BOOLEAN NOT NULL,
	`art`           VARCHAR(255),
	`description`   CLOB NOT NULL,
	`created_at`    TIMESTAMP NOT NULL,
	`last_updated`  TIMESTAMP NOT NULL,
	`published_at`  TIMESTAMP,
	`concat_master` VARCHAR(255),
	`loudness`      INT,
	`peak`          INT
);
CREATE TABLE `tracks` (
	`track_id`     BIGINT AUTO_INCREMENT PRIMARY KEY,
	`release_id`   BIGINT NOT NULL,
	`title`        VARCHAR(255) NOT NULL,
	`subtitle`     VARCHAR(255) NOT NULL,
	`slug`         VARCHAR(255) NOT NULL UNIQUE,
	`art`          VARCHAR(255),
	`master`       VARCHAR(255) NOT NULL,
	`description`  CLOB NOT NULL,
	`created_at`   TIMESTAMP NOT NULL,
	`last_updated` TIMESTAMP NOT NULL,
	`track_number` INT NOT NULL,
	`duration`     BIGINT NOT NULL,
	`loudness`     INT NOT NULL,
	`peak`         INT NOT NULL
);
CREATE TABLE `transcodes` (
	`transcode_id`    BIGINT AUTO_INCREMENT PRIMARY KEY,
	`master`          VARCHAR(255) NOT NULL,
	`format`          INT NOT NULL,
	`file`            VARCHAR(255) NOT NULL,
	`track_id`        BIGINT,
	`release_id`      BIGINT NOT NULL,
	`created_at`      TIMESTAMP NOT NULL,
	`last_downloaded` TIMESTAMP NOT NULL
);
CREATE TABLE `users` (
	`user_id`      BIGINT AUTO_INCREMENT PRIMARY KEY,
	`username`     VARCHAR(255) NOT NULL UNIQUE,
	`display_name` VARCHAR(255) NOT NULL,
	`password`     VARCHAR(255) NOT NULL,
	`admin`        BOOLEAN NOT NULL,
	`created_at`   TIMESTAMP NOT NULL,
	`last_login`   TIMESTAMP
);
CREATE TABLE `sessions` (
	`session_id`   UUID PRIMARY KEY,
	`user_id`      BIGINT NOT NULL,
	`expires`      TIMESTAMP NOT NULL
);

CREATE INDEX `tracks_release_index`
	ON `tracks` (`release_id`);
CREATE INDEX `releases_user_index`
	ON `releases` (`user_id`);
CREATE INDEX `transcodes_master_index`
	ON `transcodes` (`master`);

CREATE INDEX `tracks_number_index`
	ON `tracks` (`track_number`);

CREATE INDEX `transcodes_format_index`
	ON `transcodes` (`format`);
CREATE INDEX `transcodes_last_downloaded_index`
	ON `transcodes` (`last_downloaded`);
CREATE INDEX `transcodes_release_id_index`
	ON `transcodes` (`release_id`);
CREATE INDEX `transcodes_track_id_index`
	ON `transcodes` (`track_id`);

CREATE INDEX `sessions_expires_index`
	ON `sessions` (`expires`);

ALTER TABLE `tracks` ADD CONSTRAINT `tracks_releases`
	FOREIGN KEY (`release_id`) REFERENCES `releases`
	ON DELETE CASCADE;
ALTER TABLE `transcodes` ADD CONSTRAINT `transcodes_releases`
	FOREIGN KEY (`release_id`) REFERENCES `releases`
	ON DELETE CASCADE;
ALTER TABLE `transcodes` ADD CONSTRAINT `transcodes_tracks`
	FOREIGN KEY (`track_id`) REFERENCES `tracks`
	ON DELETE CASCADE;
ALTER TABLE `releases` ADD CONSTRAINT `releases_users`
	FOREIGN KEY (`user_id`) REFERENCES `users`
	ON DELETE CASCADE;
ALTER TABLE `sessions` ADD CONSTRAINT `sessions_users`
	FOREIGN KEY (`user_id`) REFERENCES `users`
	ON DELETE CASCADE;