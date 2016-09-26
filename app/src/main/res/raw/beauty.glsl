precision mediump float;

varying mediump vec2 textureCoordinate;

uniform sampler2D inputImageTexture;
uniform vec2 singleStepOffset;
uniform mediump float params;

const highp vec3 W = vec3(0.299,0.587,0.114);
vec2 blurCoordinates[12];

float hardLight(float color)
{
	if(color <= 0.5)
		color = color * color * 2.0;
	else
		color = 1.0 - ((1.0 - color)*(1.0 - color) * 2.0);
	return color;
}

void main(){

    vec3 centralColor = texture2D(inputImageTexture, textureCoordinate).rgb;
    blurCoordinates[0] = textureCoordinate.xy + singleStepOffset * vec2(5.0, -8.0);
    blurCoordinates[1] = textureCoordinate.xy + singleStepOffset * vec2(5.0, 8.0);
    blurCoordinates[2] = textureCoordinate.xy + singleStepOffset * vec2(-5.0, 8.0);
    blurCoordinates[3] = textureCoordinate.xy + singleStepOffset * vec2(-5.0, -8.0);
    blurCoordinates[4] = textureCoordinate.xy + singleStepOffset * vec2(8.0, -5.0);
    blurCoordinates[5] = textureCoordinate.xy + singleStepOffset * vec2(8.0, 5.0);
    blurCoordinates[6] = textureCoordinate.xy + singleStepOffset * vec2(-8.0, 5.0);
    blurCoordinates[7] = textureCoordinate.xy + singleStepOffset * vec2(-8.0, -5.0);
    blurCoordinates[8] = textureCoordinate.xy + singleStepOffset * vec2(-4.0, -4.0);
    blurCoordinates[9] = textureCoordinate.xy + singleStepOffset * vec2(-4.0, 4.0);
    blurCoordinates[10] = textureCoordinate.xy + singleStepOffset * vec2(4.0, -4.0);
    blurCoordinates[11] = textureCoordinate.xy + singleStepOffset * vec2(4.0, 4.0);

    float sampleColor = texture2D(inputImageTexture, textureCoordinate).g * 22.0;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[0]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[1]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[2]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[3]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[4]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[5]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[6]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[7]).g;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[8]).g * 2.0;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[9]).g * 2.0;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[10]).g * 2.0;
    sampleColor += texture2D(inputImageTexture, blurCoordinates[11]).g * 2.0;

    sampleColor = sampleColor / 64.0;

    float highPass = centralColor.g - sampleColor + 0.5;

    for(int i = 0; i < 5;i++)
    {
        highPass = hardLight(highPass);
    }
    float luminance = dot(centralColor, W);

    float alpha = pow(luminance, params);

    vec3 smoothColor = centralColor + (centralColor-vec3(highPass))*alpha*0.1;

    gl_FragColor = vec4(mix(smoothColor.rgb, max(smoothColor, centralColor), alpha), 1.0);
}
