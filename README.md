Yet Another Stream Encoder for Android
======================================

**yasea** is an RTMP streaming client in pure Java for Android for those who
hate JNI development. It combines the source code of both [!srs-sea](https://github.com/ossrs/srs-sea)
and [!SimpleRtmp](https://github.com/faucamp/SimpleRtmp) to encode video in
H.264 and audio in AAC by hardware and upload packets to server over RTMP.
Moreover, hardware encoding produces less CPU overhead than software does. And
the code does not depend on any native library.

Help
----

The project now can sample both video from camera and audio from microphone of
Android mobile and connect and handshake with the remote. Unfortunately it has
some problems with the correct format of RTMP packets which still can not be
identified by the server and Wireshark. Any help is welcome.
