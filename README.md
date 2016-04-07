Yet Another Stream Encoder for Android
======================================

**yasea** is an RTMP streaming client in pure Java for Android for those who
hate JNI development. It bases on the source code of both [srs-sea](https://github.com/ossrs/srs-sea)
and [SimpleRtmp](https://github.com/faucamp/SimpleRtmp) to hard encode video in
H.264 frome camera and audio from phone in AAC and upload packets to server over
RTMP. Moreover, hard encoding produces less CPU overhead than software does. And
the code does not depend on any native library.

Help
----

Unfortunately the client still has problems with the low bitrate of uploading.
I do not know well about Java optimization. Any help is welcome.
