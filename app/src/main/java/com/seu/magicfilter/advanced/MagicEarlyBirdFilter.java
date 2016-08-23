package com.seu.magicfilter.advanced;

import android.opengl.GLES20;

import net.ossrs.yasea.SrsPublisher;
import net.ossrs.yasea.R;

import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.OpenGlUtils;

public class MagicEarlyBirdFilter extends GPUImageFilter{
	private int[] inputTextureHandles = {-1,-1,-1,-1,-1};
	private int[] inputTextureUniformLocations = {-1,-1,-1,-1,-1};
	protected int mGLStrengthLocation;

	public MagicEarlyBirdFilter(){
		super(NO_FILTER_VERTEX_SHADER, OpenGlUtils.readShaderFromRawResource(R.raw.earlybird));
	}

	@Override
	protected void onDestroy() {
        super.onDestroy();
        GLES20.glDeleteTextures(inputTextureHandles.length, inputTextureHandles, 0);
        for(int i = 0; i < inputTextureHandles.length; i++)
        	inputTextureHandles[i] = -1;
    }

	@Override
	protected void onDrawArraysAfter(){
		for(int i = 0; i < inputTextureHandles.length
				&& inputTextureHandles[i] != OpenGlUtils.NO_TEXTURE; i++){
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + (i+3));
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		}
	}

	@Override
	protected void onDrawArraysPre(){
		for(int i = 0; i < inputTextureHandles.length 
				&& inputTextureHandles[i] != OpenGlUtils.NO_TEXTURE; i++){
			GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + (i+3) );
			GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureHandles[i]);
			GLES20.glUniform1i(inputTextureUniformLocations[i], (i+3));
		}
	}

	@Override
	protected void onInit(){
		super.onInit();
		for(int i=0; i < inputTextureUniformLocations.length; i++)
			inputTextureUniformLocations[i] = GLES20.glGetUniformLocation(getProgram(), "inputImageTexture"+(2+i));
		mGLStrengthLocation = GLES20.glGetUniformLocation(mGLProgId,
				"strength");
	}

	@Override
	protected void onInitialized(){
		super.onInitialized();
		setFloat(mGLStrengthLocation, 1.0f);
	    runOnDraw(new Runnable(){
		    public void run(){
		    	inputTextureHandles[0] = OpenGlUtils.loadTexture(SrsPublisher.getContext(), "filter/earlybirdcurves.png");
				inputTextureHandles[1] = OpenGlUtils.loadTexture(SrsPublisher.getContext(), "filter/earlybirdoverlaymap_new.png");
				inputTextureHandles[2] = OpenGlUtils.loadTexture(SrsPublisher.getContext(), "filter/vignettemap_new.png");
				inputTextureHandles[3] = OpenGlUtils.loadTexture(SrsPublisher.getContext(), "filter/earlybirdblowout.png");
				inputTextureHandles[4] = OpenGlUtils.loadTexture(SrsPublisher.getContext(), "filter/earlybirdmap.png");
		    }
	    });
	}
}
