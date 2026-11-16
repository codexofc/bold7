import {Config} from '@remotion/cli/config';

// WebGL in headless Chrome on macOS: ANGLE renders the 3D phone reliably.
Config.setChromiumOpenGlRenderer('angle');
Config.setVideoImageFormat('jpeg');
Config.setJpegQuality(92);
