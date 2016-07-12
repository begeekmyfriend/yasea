Yet Another Stream Encoder for Android
======================================

[![Android Arsenal](https://img.shields.io/badge/Android%20Arsenal-yasea-green.svg?style=true)](https://android-arsenal.com/details/1/3481)

**Yasea** is an Android streaming client. It is based on the source of both
[srs-sea](https://github.com/ossrs/srs-sea) and [SimpleRtmp](https://github.com/faucamp/SimpleRtmp).
It uses hard encoder for H.264/AAC and transmits over RTMP. Moreover, it runs
on Android mini API 16 (Android 4.1).

Feature
-------

- [x] Android mini API 16.
- [x] H.264/AAC hard encoding.
- [x] RTMP streaming with state callback handler.
- [x] Portrait and landscape dynamic orientation.
- [x] Front and back cameras hot switch.
- [x] Recording to MP4 while streaming.
- [ ] Authentication for RTMP server.

Test
----

You may watch the live broadcasting at [srs.net](http://www.ossrs.net/players/srs_player.html).
Remember to modify the URL by yourself. Have fun!

**NOTE** if you feel high latency, it might be the frame cache of the server or
player. So you need to open the player first and then publish to see the effect.

Other Branch
------------

If you want to stay in portrait mode ignoring dynamic orientation change when
streaming, clone yasea on brach [portrait-forever](https://github.com/begeekmyfriend/yasea/tree/portrait-forever).
