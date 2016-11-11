/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.github.faucamp.simplertmp.amf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 *
 * @author francois
 */
public class AmfNull implements AmfData {

    @Override
    public void writeTo(OutputStream out) throws IOException {
        out.write(AmfType.NULL.getValue());
    }

    @Override
    public void readFrom(InputStream in) throws IOException {
        // Skip data type byte (we assume it's already read)    
    }
    
    public static void writeNullTo(OutputStream out) throws IOException {
        out.write(AmfType.NULL.getValue());
    }

    @Override
    public int getSize() {
        return 1;
    }    
}
