attribute vec4 position;
attribute vec4 inputTextureCoordinate;

varying vec2 textureCoordinate;

void main() {
    textureCoordinate = inputTextureCoordinate.xy;
    gl_Position = position;
}
