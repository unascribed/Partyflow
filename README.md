<img src="src/main/resources/static/logo.svg" height="180" align="right"/>

# Partyflow
Partyflow is a self-hostable music release manager (think Bandcamp) with a built-in on-demand
transcoder, written in Java 17. The media formats it offers are customizable, but it ships with a
large collection of pre-defined formats tuned to provide the best balance between filesize and audio
fidelity.

Some of its headline features are gapless playback, native ReplayGain 2 support, metadata management
(including album art formats not supported by FFmpeg), and a slick lightweight JS-optional web UI
with customizable colors.

**Partyflow is not yet complete**. Some features are still missing, but the bulk of it is in place.

# Project state

## The good 😃
(What's currently working)

- In-browser setup
- Can use an embedded H2SQL database or connect to MariaDB
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
- Abstracted storage backend; currently supports S3 and local filesystem
- Volumes above 100%
- Automatic extraction and organization of existing metadata from uploaded masters
- Automatic pruning of old transcodes that haven't been downloaded lately
- Download counts that don't count multiple downloads from the same IP (anonymously, via bloom filter)
- The core is becoming increasingly nice to use

## The bad 📝
(What still needs work)

- No admin page
- No way to make new accounts
- No way to pay for releases
- No color customization yet
- Can't set the release date on releases to the past (for backcatalog)
- Can't override templates or static files
- No translation support
- If the server is stopped while a release concatenation is ongoing, the release will never finish processing

## The ugly 😦
(Assorted notes about things needing improvement)

- If you're logged in as admin it's not possible to view a release/track as a normal user
  - This is now a lot less bad as I ported over the new player to the edit page
- Halfway through a refactor to move business logic into *Api classes that also expose a JSON HTTP API
- Halfway through a refactor to put all SQL queries in one place for ease of reference
- Windows support is currently broken due to usage of /dev/zero and some other stuff
- All the SQL queries need to be confirmed to be compatible with MariaDB; I've tested a major number but not quite all
- Some stuff needs to get migrated to use Jetty APIs instead of pointlessly reimplementing it, like query strings
- All the booleans under "formats" should get moved into the database and controlled from the admin UI
- The core really needs to become a library so it can be reused

# Running it
If you're okay with it being unfinished as described above, here's what you need to do to run it:

## 1. Build it
Release JARs are not yet provided for Partyflow. You'll need to install a Java 17 JDK and build the
project like so (the Gradle wrapper will download Gradle for you):

`./gradlew build`

The resultant JAR will be in `build/libs`.

## 2. Configure it
Copy `partyflow-config.sample.jkson` to `partyflow-config.jkson` in the directory you intend to run
Partyflow in. Edit it to your liking; it's well-commented.

**The config format is not yet stable.** If you update Partyflow, your config may no longer work in
the new version due to reorganization. If you have issues, start over from the current example.

## 3. Run it
Run the Partyflow jar with Java 17 in the same directory as the configuration. The database will be
automatically initialized as described in the config.

## 4. Set it up
A secret key is printed in the log if Partyflow is started with no defined admin users. Navigating
to the Partyflow instance will redirect you to `/setup`, allowing you to create an admin user. That
secret key is required to perform the setup.

## 5. Make a release
Under "Things you can do" while logged in as admin, one of the options is "Create a release". This
will take you through the release creation flow. Once you've created the release and some tracks,
you can publish the release and send the link to others to allow them to stream it or download it in
a variety of formats.

# Other topics

## Overriding the built-in formats
Partyflow ships with 20 predefined transcode formats, for streaming and download. You can change the
config key `formats.definitions` to a file path to replace the built-in formats with your own. You
will likely want to use the [built-in formats.jkson](https://git.sleeping.town/unascribed/Partyflow/src/branch/trunk/src/main/resources/formats.jkson)
as a reference, as it's a fairly complex format.

If you need to add non-FFmpeg programs, you can add arbitrarily-named altcmds into the config in the
same place as fdkaac and qaac, and utilize them within your custom format definitions file.

If you simply want to add new formats rather than changing or removing existing ones, then you can
set `additionalDefinitions` instead, which will add additional formats on top of those already
defined. The format is the same.

## Enabling AAC support
FFmpeg has a built-in AAC encoder, but it is comically awful. As Partyflow is supposed to ship with
good defaults for audio encoding, it flat out does not support it. Instead, you will need to do one
of the following:

<details><summary><h3>1. (Easiest) Install fdkaac</h3></summary>

This is the easiest by far — it's even just available for install in a lot of distros. On Debian,
simply enabling the `contrib` repository means it's a `sudo apt install fdkaac` away.

Further information on FDK AAC can be found on the [excellent HydrogenAudio wiki](https://wiki.hydrogenaud.io/index.php?title=Fraunhofer_FDK_AAC).

Once it's installed, you can make use of it by setting `aacMode` in the config under `formats` to
"fdkaac".
</details>

<details><summary><h3>2. (Best quality, hardest) Set up qaac inside of Wine</h3></summary>

qaac is an open source wrapper tool for Apple's CoreAudio encoders, which were ported to Windows as
part of Apple Application Support — a helper library that comes with most of Apple's Windows
applications.

We have [a wiki page](https://git.sleeping.town/unascribed/Partyflow/wiki/qaac-Installation)
explaining this process.

**Note**: qaac in Wine is *very* slow.

Once it's installed, you can make use of it by setting `aacMode` in the config under `formats` to
"qaac".
</details>

<details><summary><h3>3. (Useful if you already did it) Build FFmpeg with libfdk_aac support</h3></summary>

If you've already got an FFmpeg build with libfdk_aac support, you can reduce the involved moving
parts by setting  `aacMode` in the config under `formats` to "ffmpeg-fdk". If your FFmpeg build does
not have libfdk_aac support, it is much easier to use the standalone fdkaac tool.

</details>

Once you've done any of these, you can, ***at your own risk***, set `allowEncumberedFormats` under
`formats` in the config to `true`.
