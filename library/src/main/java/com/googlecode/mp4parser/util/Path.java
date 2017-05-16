/*
 * Copyright 2012 Sebastian Annies, Hamburg
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.googlecode.mp4parser.util;


import com.coremedia.iso.IsoFile;
import com.coremedia.iso.boxes.Box;
import com.coremedia.iso.boxes.ContainerBox;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Path {

    private Path() {
    }

    static Pattern component = Pattern.compile("(....|\\.\\.)(\\[(.*)\\])?");

    public static String createPath(Box box) {
        return createPath(box, "");
    }

    private static String createPath(Box box, String path) {
        if (box instanceof IsoFile) {
            return path;
        } else {
            List<?> boxesOfBoxType = box.getParent().getBoxes(box.getClass());
            int index = boxesOfBoxType.indexOf(box);
            path = String.format("/%s[%d]", box.getType(), index) + path;

            return createPath(box.getParent(), path);
        }
    }

    public static Box getPath(Box box, String path) {
        List<Box> all = getPaths(box, path);
        return all.isEmpty() ? null : all.get(0);
    }


    public static List<Box> getPaths(Box box, String path) {
        if (path.startsWith("/")) {
            Box isoFile = box;
            while (isoFile.getParent() != null) {
                isoFile = isoFile.getParent();
            }
            assert isoFile instanceof IsoFile : isoFile.getType() + " has no parent";
            return getPaths(isoFile, path.substring(1));
        } else if (path.isEmpty()) {
            return Collections.singletonList(box);
        } else {
            String later;
            String now;
            if (path.contains("/")) {
                later = path.substring(path.indexOf('/') + 1);
                now = path.substring(0, path.indexOf('/'));
            } else {
                now = path;
                later = "";
            }

            Matcher m = component.matcher(now);
            if (m.matches()) {
                String type = m.group(1);
                if ("..".equals(type)) {
                    return getPaths(box.getParent(), later);
                } else {
                    int index = -1;
                    if (m.group(2) != null) {
                        // we have a specific index
                        String indexString = m.group(3);
                        index = Integer.parseInt(indexString);
                    }
                    List<Box> children = new LinkedList<Box>();
                    int currentIndex = 0;
                    for (Box box1 : ((ContainerBox) box).getBoxes()) {
                        if (box1.getType().matches(type)) {
                            if (index == -1 || index == currentIndex) {
                                children.addAll(getPaths(box1, later));
                            }
                            currentIndex++;
                        }
                    }
                    return children;
                }
            } else {
                throw new RuntimeException(now + " is invalid path.");
            }
        }

    }


    public static boolean isContained(Box box, String path) {
        assert path.startsWith("/") : "Absolute path required";
        return getPaths(box, path).contains(box);
    }
}
