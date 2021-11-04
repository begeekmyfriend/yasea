#extension GL_OES_EGL_image_external : require

precision mediump float;

uniform samplerExternalOES inputImageTexture;
uniform vec2 singleStepOffset;

varying vec2 textureCoordinate;

const vec4 params = vec4(0.748, 0.874, 0.241, 0.241);
const vec3 W = vec3(0.299,0.587,0.114);
const mat3 saturateMatrix = mat3(
                                1.1102,-0.0598,-0.061,
                                -0.0774,1.0826,-0.1186,
                                -0.0228,-0.0228,1.1772);

vec2 blurCoordinates[24];

vec3 rgb2lab(vec3 color) {
    float L = 0.3811 * color.b + 0.5783 * color.g + 0.0402 * color.r;
    float M = 0.1967 * color.b + 0.7244 * color.g + 0.0782 * color.r;
    float S = 0.0241 * color.b + 0.1288 * color.g + 0.8444 * color.r;
    if (L == 0.0) L = 1.0;
    if (M == 0.0) M = 1.0;
    if (S == 0.0) S = 1.0;
    L = log(L);
    M = log(M);
    S = log(S);
    float ll = (L + M + S) / sqrt(3.0);
    float aa = (L + M - 2.0 * S) / sqrt(6.0);
    float bb = (L - M) / sqrt(2.0);
    return vec3(ll, aa, bb);
}

vec3 lab2rgb(vec3 lab) {
    float L = lab.r / sqrt(3.0) + lab.g / sqrt(6.0) + lab.b / sqrt(2.0);
    float M = lab.r / sqrt(3.0) + lab.g / sqrt(6.0) - lab.b / sqrt(2.0);
    float S = lab.r / sqrt(3.0) - 2.0 * lab.g / sqrt(6.0);
    L = exp(L);
    M = exp(M);
    S = exp(S);
    float b = 4.4679 * L - 3.5873 * M + 0.1193 * S;
    float g = -1.2186 * L + 2.3809 * M - 0.1624 * S;
    float r = -0.0497 * L - 0.2439 * M + 1.2045 * S;
    if (b > 1.0) b = 1.0;
    if (b < 0.0) b = 0.0;
    if (g > 1.0) g = 1.0;
    if (g < 0.0) g = 0.0;
    if (r > 1.0) r = 1.0;
    if (r < 0.0) r = 0.0;
    return vec3(r,g,b);
}

float skinMask(vec3 color) {
    vec3 lab = rgb2lab(color); // return values in the range [0, 1]
    float a = smoothstep(0.45, 0.55, lab.g);
    float b = smoothstep(0.46, 0.54, lab.b);
    float c = 1.0 - smoothstep(0.9, 1.0, length(lab.gb));
    float d = 1.0 - smoothstep(0.98, 1.02, lab.r);
    return min(min(min(a, b), c), d);
}

float hardLight(float color) {
    if(color <= 0.5) {
        color = color * color * 2.0;
    } else {
        color = 1.0 - ((1.0 - color) * (1.0 - color) * 2.0);
    }
    return color;
}

void main() {
    vec3 centralColor = texture2D(inputImageTexture, textureCoordinate).rgb;
    //float mask = skinMask(centralColor);
    if (true) {
        blurCoordinates[0] = textureCoordinate.xy + singleStepOffset * vec2(0.0, -10.0);
        blurCoordinates[1] = textureCoordinate.xy + singleStepOffset * vec2(0.0, 10.0);
        blurCoordinates[2] = textureCoordinate.xy + singleStepOffset * vec2(-10.0, 0.0);
        blurCoordinates[3] = textureCoordinate.xy + singleStepOffset * vec2(10.0, 0.0);
        blurCoordinates[4] = textureCoordinate.xy + singleStepOffset * vec2(5.0, -8.0);
        blurCoordinates[5] = textureCoordinate.xy + singleStepOffset * vec2(5.0, 8.0);
        blurCoordinates[6] = textureCoordinate.xy + singleStepOffset * vec2(-5.0, 8.0);
        blurCoordinates[7] = textureCoordinate.xy + singleStepOffset * vec2(-5.0, -8.0);
        blurCoordinates[8] = textureCoordinate.xy + singleStepOffset * vec2(8.0, -5.0);
        blurCoordinates[9] = textureCoordinate.xy + singleStepOffset * vec2(8.0, 5.0);
        blurCoordinates[10] = textureCoordinate.xy + singleStepOffset * vec2(-8.0, 5.0);
        blurCoordinates[11] = textureCoordinate.xy + singleStepOffset * vec2(-8.0, -5.0);
        blurCoordinates[12] = textureCoordinate.xy + singleStepOffset * vec2(0.0, -6.0);
        blurCoordinates[13] = textureCoordinate.xy + singleStepOffset * vec2(0.0, 6.0);
        blurCoordinates[14] = textureCoordinate.xy + singleStepOffset * vec2(6.0, 0.0);
        blurCoordinates[15] = textureCoordinate.xy + singleStepOffset * vec2(-6.0, 0.0);
        blurCoordinates[16] = textureCoordinate.xy + singleStepOffset * vec2(-4.0, -4.0);
        blurCoordinates[17] = textureCoordinate.xy + singleStepOffset * vec2(-4.0, 4.0);
        blurCoordinates[18] = textureCoordinate.xy + singleStepOffset * vec2(4.0, -4.0);
        blurCoordinates[19] = textureCoordinate.xy + singleStepOffset * vec2(4.0, 4.0);
        blurCoordinates[20] = textureCoordinate.xy + singleStepOffset * vec2(-2.0, -2.0);
        blurCoordinates[21] = textureCoordinate.xy + singleStepOffset * vec2(-2.0, 2.0);
        blurCoordinates[22] = textureCoordinate.xy + singleStepOffset * vec2(2.0, -2.0);
        blurCoordinates[23] = textureCoordinate.xy + singleStepOffset * vec2(2.0, 2.0);

        float sampleColor = centralColor.g * 22.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[0]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[1]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[2]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[3]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[4]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[5]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[6]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[7]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[8]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[9]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[10]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[11]).g;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[12]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[13]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[14]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[15]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[16]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[17]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[18]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[19]).g * 2.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[20]).g * 3.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[21]).g * 3.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[22]).g * 3.0;
        sampleColor += texture2D(inputImageTexture, blurCoordinates[23]).g * 3.0;
        sampleColor = sampleColor / 62.0;

        float highPass = centralColor.g - sampleColor + 0.5;
        for (int i = 0; i < 5; i++) {
            highPass = hardLight(highPass);
        }
        float luminance = dot(centralColor, W);
        float alpha = pow(luminance, params.r);

        vec3 smoothColor = centralColor + (centralColor-vec3(highPass))*alpha*0.1;

        smoothColor.r = clamp(pow(smoothColor.r, params.g), 0.0, 1.0);
        smoothColor.g = clamp(pow(smoothColor.g, params.g), 0.0, 1.0);
        smoothColor.b = clamp(pow(smoothColor.b, params.g), 0.0, 1.0);

        vec3 screen = vec3(1.0) - (vec3(1.0)-smoothColor) * (vec3(1.0)-centralColor);
        vec3 lighten = max(smoothColor, centralColor);
        vec3 softLight = 2.0 * centralColor*smoothColor + centralColor*centralColor
        - 2.0 * centralColor*centralColor * smoothColor;

        gl_FragColor = vec4(mix(centralColor, screen, alpha), 1.0);
        gl_FragColor.rgb = mix(gl_FragColor.rgb, lighten, alpha);
        gl_FragColor.rgb = mix(gl_FragColor.rgb, softLight, params.b);

        vec3 satColor = gl_FragColor.rgb * saturateMatrix;
        gl_FragColor.rgb = mix(gl_FragColor.rgb, satColor, params.a);
        gl_FragColor.rgb = vec3(gl_FragColor.rgb + vec3(-0.096));
    } else {
        gl_FragColor.rgb = centralColor;
    }
}