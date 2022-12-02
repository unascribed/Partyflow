# <img src="logo.png" alt="Partyflow!" height="64"/>
Partyflow is a self-hostable music release manager (think Bandcamp) with a built-in on-demand
transcoder, written in Java 17. The media formats it offers are customizable, but it ships with a
large collection of pre-defined formats tuned to provide the best balance between filesize and audio
fidelity.

Some of its headline features are gapless playback, native ReplayGain 2 support, metadata management
(including album art formats not supported by FFmpeg), and a slick lightweight JS-optional web UI
with customizable colors.

**Partyflow is not yet complete**. Some features are still missing, but the bulk of it is in place.

## What works

- In-browser setup
- Embedded H2SQL database
- Album and track art
- Creating releases
- Adding tracks
- On-the-fly transcoding
- Gapless playback
- ReplayGain 2
- Downloading tracks and release ZIPs
- Giving tracks lyrics and descriptions (Markdown when JS is off, WYSIWYG when JS is on)
- fdkaac and qaac support for AAC encoding (optional)
- yt-dlp integration (e.g. download FLACs directly from yt-dlp)
- All names can contain arbitrary Unicode
- Automatic AGPL compliance
- Local filesystem storage
- Volumes above 100%

## What doesn't

- No admin page
- No way to make new accounts
- No way to pay for releases
- Color customization is still WIP
- No external database support (MariaDB planned)
- Can't set the release date on releases to the past (for backcatalog)
- Can't override templates or static files
- No translation support
- Remote storage (e.g. S3)

## Other missing stuff

- A logo that isn't just :tada: from Twemoji
- For some reason parallel transcodes just don't work

## Running it
If you're okay with it being unfinished as described above, here's what you need to do to run it.

### 1. Build it
Release JARs are not yet provided for Partyflow. You'll need to install a Java 17 JDK and build the
project like so (the Gradle wrapper will download Gradle for you):

`./gradlew build`

The resultant JAR will be in `build/libs`.

### 2. Config it
Copy `partyflow-config.sample.jkson` to `partyflow-config.jkson` in the directory you intend to run
Partyflow in. Edit it to your liking; it's well-commented.

### 3. Run it
Run the Partyflow jar with Java 17 in the same directory as the configuration. An H2SQL database
will be automatically created and initialized at the location in the config.

### 4. Set it up
A secret key is printed in the log if Partyflow is started with no defined admin users. Navigating
to the Partyflow instance will redirect you to `/setup`, allowing you to create an admin user. That
secret key is required to perform the setup.

### 5. Make a release
Under "Things you can do" while logged in as admin, one of the options is "Create a release". This
will take you through the release creation flow. Once you've created the release and some tracks,
you can publish the release and send the link to others to allow them to stream it or download it in
a variety of formats.

## Overriding the built-in formats
Partyflow ships with 20 predefined transcode formats, for streaming and download. You can change the
config key `formats.definitions` to a file path to replace the built-in formats with your own. You
will likely want to use the [built-in formats.jkson](https://git.sleeping.town/unascribed/Partyflow/src/branch/trunk/src/main/resources/formats.jkson)
as a reference, as it's a fairly complex format.

If you need to add non-FFmpeg programs, you can add arbitrarily-named altcmds into the config in the
same place as fdkaac and qaac, and utilize them within your custom format definitions file.
