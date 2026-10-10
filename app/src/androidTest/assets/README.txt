Synthetic six-picture 1280x800 H.264 test pattern, generated using FFmpeg/libx264.
Contains no physical kart images or deployment data. Exercises MediaCodec surface
rendering only; real LSP parsing is tested separately.

ffmpeg -f lavfi -i testsrc2=size=1280x800:rate=5 -frames:v 6 -c:v libx264 -threads 2 -profile:v high -level:v 3.2 -preset fast -tune zerolatency -x264-params aud=1:keyint=3:min-keyint=3:scenecut=0 -f h264 synthetic-video.h264
