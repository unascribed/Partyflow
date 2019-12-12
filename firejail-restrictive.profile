# Firejail config file that prevents pretty much everything
# Works fine for using FFmpeg and ImageMagick via stdin/stdout

# quiet

caps.drop all
net none
no3d
nodvd
nosound
notv
novideo
nonewprivs
noroot

x11 none
shell none
seccomp
protocol unix

private
private-bin ffmpeg convert convert-im6.q16 magick
private-dev
private-tmp

memory-deny-write-execute