Yet Another Stream Encoder for Android
======================================

[![Android Arsenal](https://img.shields.io/badge/Android%20Arsenal-yasea-green.svg?style=true)](https://android-arsenal.com/details/1/3481)

**Yasea** is an Android streaming client. It encodes YUV and PCM data from
camera and microphone to H.264/AAC, encapsulates in FLV and transmits over RTMP.

Feature
-------

- [x] Android mini API 16.
- [x] H.264/AAC hard encoding.
- [x] H.264 soft encoding.
- [x] RTMP streaming with state callback handler.
- [x] Portrait and landscape dynamic orientation.
- [x] Front and back cameras hot switch.
- [x] Recording to MP4 while streaming.

Test
----

You may build your own private RTMP server [srs](https://github.com/ossrs/srs/tree/2.0release).
Remember to modify the URL by yourself. Have fun!

**NOTE** if you feel high latency, please check your bandwidth limits and player buffering.

Other Branch
------------

If you want to stay in portrait mode ignoring dynamic orientation change when
streaming, clone yasea on brach [portrait-forever](https://github.com/begeekmyfriend/yasea/tree/portrait-forever).

Thanks
------

- [srs-sea](https://github.com/ossrs/srs-sea)
- [SimpleRtmp](https://github.com/faucamp/SimpleRtmp)
- [x264](http://www.videolan.org/developers/x264.html)
- [mp4parser](https://android.googlesource.com/platform/external/mp4parser)

Sponsor
-------

- [dotEngine](https://dot.cc) -- A brilliant realtime multimedia communication service vendor with open cross-platform SDK ([github](https://github.com/dotEngine)).
