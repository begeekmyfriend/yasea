Yet Another Stream Encoder for Android
======================================

**yasea** is an RTMP streaming client in pure Java for Android for those who
hate JNI development. It is based on the source code of both [srs-sea](https://github.com/ossrs/srs-sea)
and [SimpleRtmp](https://github.com/faucamp/SimpleRtmp) to hard encode video in
H.264 frome camera and audio from phone in AAC and upload packets to server over
RTMP. Moreover, hard encoding produces less CPU overhead than software does. And
the code does not depend on any native library.

You may watch the live broadcast at [srs.net](http://www.ossrs.net/players/srs_player.html). Note to change the URL yourself. Have fun!
