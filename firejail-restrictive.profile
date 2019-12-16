# Firejail config file for sandboxing FFmpeg and ImageMagick
# (WIP, mostly untested. Will get this working "for sure" soon.)

quiet

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

nice 15

private
private-bin ffmpeg convert convert-im6.q16 magick
private-dev
private-tmp

whitelist /tmp/partyflow/work

memory-deny-write-execute