/*
 * Copyright (c) 2009-2024 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.renderer.opengl;

import com.jme3.material.RenderState;
import com.jme3.material.RenderState.BlendFunc;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.StencilOperation;
import com.jme3.material.RenderState.TestFunction;
import com.jme3.math.*;
import com.jme3.opencl.OpenCLObjectManager;
import com.jme3.renderer.*;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mesh.Mode;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.VertexBuffer.Format;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.VertexBuffer.Usage;
import com.jme3.shader.*;
import com.jme3.shader.Shader.ShaderSource;
import com.jme3.shader.Shader.ShaderType;
import com.jme3.system.JmeSystem;
import com.jme3.system.Platform;
import com.jme3.shader.ShaderBufferBlock.BufferType;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.shader.bufferobject.BufferRegion;
import com.jme3.shader.bufferobject.DirtyRegionsIterator;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.FrameBuffer.RenderBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.Texture.ShadowCompareMode;
import com.jme3.texture.Texture.WrapAxis;
import com.jme3.texture.TextureImage;
import com.jme3.texture.image.LastTextureState;
import com.jme3.util.BufferUtils;
import com.jme3.util.ListMap;
import com.jme3.util.MipMapGenerator;
import com.jme3.util.NativeObject;
import com.jme3.util.NativeObjectManager;

import jme3tools.shader.ShaderDebug;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class GLRenderer implements Renderer {

    private static final Logger logger = Logger.getLogger(GLRenderer.class.getName());
    private static final boolean VALIDATE_SHADER = false;
    private static final Pattern GLVERSION_PATTERN = Pattern.compile(".*?(\\d+)\\.(\\d+).*");

    private final ByteBuffer nameBuf = BufferUtils.createByteBuffer(250);
    private final FloatBuffer floatBuf16 = BufferUtils.createFloatBuffer(16);
    private final StringBuilder stringBuf = new StringBuilder(250);
    private final IntBuffer intBuf1 = BufferUtils.createIntBuffer(1);
    private final IntBuffer intBuf16 = BufferUtils.createIntBuffer(16);
    private final RenderContext context = new RenderContext();
    private final NativeObjectManager objManager = new NativeObjectManager();
    private final EnumSet<Caps> caps = EnumSet.noneOf(Caps.class);
    private final EnumMap<Limits, Integer> limits = new EnumMap<>(Limits.class);

    private FrameBuffer mainFbOverride = null;
    private int defaultFBO = 0;
    private final Statistics statistics = new Statistics();
    private int vpX, vpY, vpW, vpH;
    private int clipX, clipY, clipW, clipH;
    private int defaultAnisotropicFilter = 1;
    private boolean linearizeSrgbImages;
    private HashSet<String> extensions;
    private boolean generateMipmapsForFramebuffers = true;

    private final GL gl;
    private final GL2 gl2;
    private final GL3 gl3;
    private final GL4 gl4;
    private final GLExt glext;
    private final GLFbo glfbo;
    private final TextureUtil texUtil;
    private boolean debug = false;
    private int debugGroupId = 0;


    public GLRenderer(GL gl, GLExt glext, GLFbo glfbo) {
        this.gl = gl;
        this.gl2 = gl instanceof GL2 ? (GL2)gl : null;
        this.gl3 = gl instanceof GL3 ? (GL3)gl : null;
        this.gl4 = gl instanceof GL4 ? (GL4)gl : null;
        this.glfbo = glfbo;
        this.glext = glext;
        this.texUtil = new TextureUtil(gl, gl2, glext);
    }
    
    /**
     * Enable/Disable default automatic generation of mipmaps for framebuffers
     * @param v  Default is true
     */
    public void setGenerateMipmapsForFrameBuffer(boolean v) {
        generateMipmapsForFramebuffers = v;
    }

    public void setDebugEnabled(boolean v) {
        debug = v;
    }

    @Override
    public void popDebugGroup() {
        if (debug && caps.contains(Caps.GLDebug)) {
            glext.glPopDebugGroup();
            debugGroupId--;
        }
    }

    @Override
    public void pushDebugGroup(String name) {
        if (debug && caps.contains(Caps.GLDebug)) {
            if (name == null) name = "Group " + debugGroupId;
            glext.glPushDebugGroup(GLExt.GL_DEBUG_SOURCE_APPLICATION, debugGroupId, name);
            debugGroupId++;
        }
    }

    

    @Override
    public Statistics getStatistics() {
        return statistics;
    }

    @Override 
    public EnumSet<Caps> getCaps() {
        return caps;
    }

    // Not making public yet ...
    @Override
    public EnumMap<Limits, Integer> getLimits() {
        return limits;
    }

    private HashSet<String> loadExtensions() {
        HashSet<String> extensionSet = new HashSet<>(64);
        if (caps.contains(Caps.OpenGL30)) {
            // If OpenGL3+ is available, use the non-deprecated way
            // of getting supported extensions.
            gl3.glGetInteger(GL3.GL_NUM_EXTENSIONS, intBuf16);
            int extensionCount = intBuf16.get(0);
            for (int i = 0; i < extensionCount; i++) {
                String extension = gl3.glGetString(GL.GL_EXTENSIONS, i);
                extensionSet.add(extension);
            }
        } else {
            extensionSet.addAll(Arrays.asList(gl.glGetString(GL.GL_EXTENSIONS).split(" ")));
        }
        return extensionSet;
    }

    public static boolean isWebGL(String version) {
        return version.contains("WebGL");
    }

    public static int extractVersion(String version) {
        if (version.startsWith("WebGL 2.0")) return 300;
        Matcher m = GLVERSION_PATTERN.matcher(version);
        if (m.matches()) {
            int major = Integer.parseInt(m.group(1));
            int minor = Integer.parseInt(m.group(2));
            if (minor >= 10 && minor % 10 == 0) {
                // some versions can look like "1.30" instead of "1.3".
                // make sure to correct for this
                minor /= 10;
            }
            return major * 100 + minor * 10;
        } else {
            return -1;
        }
    }

    private boolean hasExtension(String extensionName) {
        return extensions.contains(extensionName);
    }

    private void loadCapabilitiesES() {
        String version = gl.glGetString(GL.GL_VERSION);
        int oglVer = extractVersion(version);
        if (isWebGL(version)) {
            caps.add(Caps.WebGL);       
        }
        caps.add(Caps.GLSL100);
        caps.add(Caps.OpenGLES20);

        caps.add(Caps.Multisample);

        if (oglVer >= 300) {
            caps.add(Caps.OpenGLES30);
            caps.add(Caps.GLSL300);
            // Instancing is core in GLES300
            caps.add(Caps.MeshInstancing);
        }
        if (oglVer >= 310) {
            caps.add(Caps.OpenGLES31);
            caps.add(Caps.GLSL310);
        }
        if (oglVer >= 320) {
            caps.add(Caps.OpenGLES32);
            caps.add(Caps.GLSL320);
            caps.add(Caps.GeometryShader);
            caps.add(Caps.TesselationShader);
        }
        // Important: Do not add OpenGL20 - that's the desktop capability!
    }

    private void loadCapabilitiesGL2() {
        int oglVer = extractVersion(gl.glGetString(GL.GL_VERSION));

        if (oglVer >= 200) {
            caps.add(Caps.OpenGL20);
            if (oglVer >= 210) {
                caps.add(Caps.OpenGL21);
            }
            if (oglVer >= 300) {
                caps.add(Caps.OpenGL30);
            }
            if (oglVer >= 310) {
                caps.add(Caps.OpenGL31);
            }
            if (oglVer >= 320) {
                caps.add(Caps.OpenGL32);
            }
            if (oglVer >= 330) {
                caps.add(Caps.OpenGL33);
                caps.add(Caps.GeometryShader);
            }
            if (oglVer >= 400) {
                caps.add(Caps.OpenGL40);
                caps.add(Caps.TesselationShader);
            }
            if (oglVer >= 410) {
                caps.add(Caps.OpenGL41);
            }
            if (oglVer >= 420) {
                caps.add(Caps.OpenGL42);
            }
            if (oglVer >= 430) {
                caps.add(Caps.OpenGL43);
            }
            if (oglVer >= 440) {
                caps.add(Caps.OpenGL44);
            }
            if (oglVer >= 450) {
                caps.add(Caps.OpenGL45);
            }
        }

        int glslVer = extractVersion(gl.glGetString(GL.GL_SHADING_LANGUAGE_VERSION));

        switch (glslVer) {
            default:
                if (glslVer < 400) {
                    break;
                }
                // so that future OpenGL revisions won't break jme3
                // fall through intentional
            case 450:
                caps.add(Caps.GLSL450);
            case 440:
                caps.add(Caps.GLSL440);
            case 430:
                caps.add(Caps.GLSL430);
            case 420:
                caps.add(Caps.GLSL420);
            case 410:
                caps.add(Caps.GLSL410);
            case 400:
                caps.add(Caps.GLSL400);
            case 330:
                caps.add(Caps.GLSL330);
            case 150:
                caps.add(Caps.GLSL150);
            case 140:
                caps.add(Caps.GLSL140);
            case 130:
                caps.add(Caps.GLSL130);
            case 120:
                caps.add(Caps.GLSL120);
            case 110:
                caps.add(Caps.GLSL110);
            case 100:
                caps.add(Caps.GLSL100);
                break;
        }

        // Workaround, always assume we support GLSL100 & GLSL110
        // Supporting OpenGL 2.0 means supporting GLSL 1.10.
        caps.add(Caps.GLSL110);
        caps.add(Caps.GLSL100);

        // Fix issue in TestRenderToMemory when GL.GL_FRONT is the main
        // buffer being used.
        context.initialDrawBuf = getInteger(GL2.GL_DRAW_BUFFER);
        context.initialReadBuf = getInteger(GL2.GL_READ_BUFFER);

        // XXX: This has to be GL.GL_BACK for canvas on Mac
        // Since initialDrawBuf is GL.GL_FRONT for pbuffer, gotta
        // change this value later on ...
//        initialDrawBuf = GL.GL_BACK;
//        initialReadBuf = GL.GL_BACK;
    }

    private void loadCapabilitiesCommon() {
        extensions = loadExtensions();

        limits.put(Limits.VertexTextureUnits, getInteger(GL.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS));
        if (limits.get(Limits.VertexTextureUnits) > 0) {
            caps.add(Caps.VertexTextureFetch);
        }

        limits.put(Limits.FragmentTextureUnits, getInteger(GL.GL_MAX_TEXTURE_IMAGE_UNITS));

        if (caps.contains(Caps.OpenGLES20)) {
            limits.put(Limits.FragmentUniformVectors, getInteger(GL.GL_MAX_FRAGMENT_UNIFORM_VECTORS));
            limits.put(Limits.VertexUniformVectors, getInteger(GL.GL_MAX_VERTEX_UNIFORM_VECTORS));
        } else {
            limits.put(Limits.FragmentUniformVectors, getInteger(GL.GL_MAX_FRAGMENT_UNIFORM_COMPONENTS) / 4);
            limits.put(Limits.VertexUniformVectors, getInteger(GL.GL_MAX_VERTEX_UNIFORM_COMPONENTS) / 4);
        }

        limits.put(Limits.VertexAttributes, getInteger(GL.GL_MAX_VERTEX_ATTRIBS));
        limits.put(Limits.TextureSize, getInteger(GL.GL_MAX_TEXTURE_SIZE));
        limits.put(Limits.CubemapSize, getInteger(GL.GL_MAX_CUBE_MAP_TEXTURE_SIZE));

        if (hasExtension("GL_ARB_draw_instanced") &&
                hasExtension("GL_ARB_instanced_arrays")) {
            // TODO: If there were a way to call the EXT extension for GLES2, should check also (hasExtension("GL_EXT_draw_instanced") && hasExtension("GL_EXT_instanced_arrays"))
            caps.add(Caps.MeshInstancing);
        }

        if (hasExtension("GL_OES_element_index_uint") || gl2 != null) {
            caps.add(Caps.IntegerIndexBuffer);
        }

        if (hasExtension("GL_ARB_texture_buffer_object")) {
            caps.add(Caps.TextureBuffer);
        }

        // == texture format extensions ==

        boolean hasFloatTexture;

        hasFloatTexture = hasExtension("GL_OES_texture_half_float") &&
                hasExtension("GL_OES_texture_float");

        if (!hasFloatTexture) {
            hasFloatTexture = hasExtension("GL_ARB_texture_float") &&
                    hasExtension("GL_ARB_half_float_pixel");

            if (!hasFloatTexture) {
                hasFloatTexture = caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30);
            }
        }

        if (hasFloatTexture) {
            caps.add(Caps.FloatTexture);
        }

        // integer texture format extensions
        if(hasExtension("GL_EXT_texture_integer") || caps.contains(Caps.OpenGL30))
            caps.add(Caps.IntegerTexture);

        if (hasExtension("GL_OES_depth_texture") || gl2 != null) {
            caps.add(Caps.DepthTexture);
        }

        if (hasExtension("GL_OES_depth24")) {
            caps.add(Caps.Depth24);
        }

        if (hasExtension("GL_OES_rgb8_rgba8") ||
                hasExtension("GL_ARM_rgba8") ||
                hasExtension("GL_EXT_texture_format_BGRA8888")) {
            caps.add(Caps.Rgba8);
        }

        if (caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30) || hasExtension("GL_OES_packed_depth_stencil")) {
            caps.add(Caps.PackedDepthStencilBuffer);
        }

        if (hasExtension("GL_ARB_color_buffer_float") &&
                hasExtension("GL_ARB_half_float_pixel")
                ||caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30)) {
            // XXX: Require both 16- and 32-bit float support for FloatColorBuffer.
            caps.add(Caps.FloatColorBuffer);
            caps.add(Caps.FloatColorBufferRGBA);
            if (!caps.contains(Caps.OpenGLES30)) {
                caps.add(Caps.FloatColorBufferRGB);
            }
        }

        if (caps.contains(Caps.OpenGLES30) || hasExtension("GL_ARB_depth_buffer_float")) {
            caps.add(Caps.FloatDepthBuffer);
        }

        if ((hasExtension("GL_EXT_packed_float") && hasFloatTexture) ||
                caps.contains(Caps.OpenGL30)) {
            // Either OpenGL3 is available or both packed_float & half_float_pixel.
            caps.add(Caps.PackedFloatColorBuffer);
            caps.add(Caps.PackedFloatTexture);
        }

        if (hasExtension("GL_EXT_texture_shared_exponent") || caps.contains(Caps.OpenGL30)) {
            caps.add(Caps.SharedExponentTexture);
        }

        if (hasExtension("GL_EXT_texture_compression_s3tc")) {
            caps.add(Caps.TextureCompressionS3TC);
        }

        if (hasExtension("GL_ARB_texture_compression_bptc")) {
            caps.add(Caps.TextureCompressionBPTC);
        }

        if (hasExtension("GL_EXT_texture_compression_rgtc")) {
            caps.add(Caps.TextureCompressionRGTC);
        }

        if (hasExtension("GL_ARB_ES3_compatibility")) {
            caps.add(Caps.TextureCompressionETC2);
            caps.add(Caps.TextureCompressionETC1);
        } else if (hasExtension("GL_OES_compressed_ETC1_RGB8_texture")) {
            caps.add(Caps.TextureCompressionETC1);
        }

        // == end texture format extensions ==

        if (hasExtension("GL_ARB_vertex_array_object") || caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30) ) {
            caps.add(Caps.VertexBufferArray);
        }

        if (hasExtension("GL_ARB_texture_non_power_of_two") ||
                hasExtension("GL_OES_texture_npot") ||
                caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30)) {
            caps.add(Caps.NonPowerOfTwoTextures);
        } else {
            logger.log(Level.WARNING, "Your graphics card does not "
                    + "support non-power-of-2 textures. "
                    + "Some features might not work.");
        }

        if (caps.contains(Caps.OpenGLES20)) {
            // OpenGL ES 2 has some limited support for NPOT textures
            caps.add(Caps.PartialNonPowerOfTwoTextures);
        }

        if (hasExtension("GL_EXT_texture_array") || caps.contains(Caps.OpenGL30) ||  caps.contains(Caps.OpenGLES30)) {
            caps.add(Caps.TextureArray);
        }

        if (hasExtension("GL_EXT_texture_filter_anisotropic")) {
            caps.add(Caps.TextureFilterAnisotropic);
            limits.put(Limits.TextureAnisotropy, getInteger(GLExt.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT));
        }

        if (hasExtension("GL_EXT_framebuffer_object")
                || caps.contains(Caps.OpenGL30)
                || caps.contains(Caps.OpenGLES20)) {
            caps.add(Caps.FrameBuffer);

            limits.put(Limits.RenderBufferSize, getInteger(GLFbo.GL_MAX_RENDERBUFFER_SIZE_EXT));
            limits.put(Limits.FrameBufferAttachments, getInteger(GLFbo.GL_MAX_COLOR_ATTACHMENTS_EXT));

            if (hasExtension("GL_EXT_framebuffer_blit") || caps.contains(Caps.OpenGL30)  || caps.contains(Caps.OpenGLES30)) {
                caps.add(Caps.FrameBufferBlit);
            }

            if (hasExtension("GL_EXT_framebuffer_multisample") || caps.contains(Caps.OpenGLES30)) {
                caps.add(Caps.FrameBufferMultisample);
                limits.put(Limits.FrameBufferSamples, getInteger(GLExt.GL_MAX_SAMPLES_EXT));
            }

            if (hasExtension("GL_ARB_texture_multisample") || caps.contains(Caps.OpenGLES31)
                    || (JmeSystem.getPlatform().getOs() == Platform.Os.MacOS
                            && caps.contains(Caps.OpenGL32))) { // GLES31 does not fully support it
                caps.add(Caps.TextureMultisample);
                limits.put(Limits.ColorTextureSamples, getInteger(GLExt.GL_MAX_COLOR_TEXTURE_SAMPLES));
                limits.put(Limits.DepthTextureSamples, getInteger(GLExt.GL_MAX_DEPTH_TEXTURE_SAMPLES));
                if (!limits.containsKey(Limits.FrameBufferSamples)) {
                    // In case they want to query samples on main FB ...
                    limits.put(Limits.FrameBufferSamples, limits.get(Limits.ColorTextureSamples));
                }
            }

            if (hasExtension("GL_ARB_draw_buffers") || caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30)) {
                limits.put(Limits.FrameBufferMrtAttachments, getInteger(GLExt.GL_MAX_DRAW_BUFFERS_ARB));
                if (limits.get(Limits.FrameBufferMrtAttachments) > 1) {
                    caps.add(Caps.FrameBufferMRT);
                }
            } else {
                limits.put(Limits.FrameBufferMrtAttachments, 1);
            }
        }

        if (hasExtension("GL_ARB_multisample") /*|| caps.contains(Caps.OpenGLES20)*/) {
            boolean available = getInteger(GLExt.GL_SAMPLE_BUFFERS_ARB) != 0;
            int samples = getInteger(GLExt.GL_SAMPLES_ARB);
            logger.log(Level.FINER, "Samples: {0}", samples);
            boolean enabled = gl.glIsEnabled(GLExt.GL_MULTISAMPLE_ARB);
            if (samples > 0 && available && !enabled) {
                // Doesn't seem to be necessary. OGL spec says it's always
                // set by default?
                gl.glEnable(GLExt.GL_MULTISAMPLE_ARB);
            }
            caps.add(Caps.Multisample);
        }

        // Supports sRGB pipeline.
        if ( (hasExtension("GL_ARB_framebuffer_sRGB") && hasExtension("GL_EXT_texture_sRGB"))
                || caps.contains(Caps.OpenGL30) || caps.contains(Caps.OpenGLES30)) {
            caps.add(Caps.Srgb);
        }

        // Supports seamless cubemap
        if (hasExtension("GL_ARB_seamless_cube_map") || caps.contains(Caps.OpenGL32)) {
            caps.add(Caps.SeamlessCubemap);
        }

        if ((caps.contains(Caps.OpenGLES30) || caps.contains(Caps.OpenGL32)) && !hasExtension("GL_ARB_compatibility")) {
            if (JmeSystem.getPlatform().getOs() != Platform.Os.iOS) { // some features are not supported on iOS
                caps.add(Caps.CoreProfile);
            }
        }

        if (hasExtension("GL_ARB_get_program_binary")) {
            int binaryFormats = getInteger(GLExt.GL_NUM_PROGRAM_BINARY_FORMATS);
            if (binaryFormats > 0) {
                caps.add(Caps.BinaryShader);
            }
        }

        if (hasExtension("GL_OES_geometry_shader") || hasExtension("GL_EXT_geometry_shader")) {
            caps.add(Caps.GeometryShader);
        }

        if (hasExtension("GL_OES_tessellation_shader") || hasExtension("GL_EXT_tessellation_shader")) {
            caps.add(Caps.TesselationShader);
        }

        if (hasExtension("GL_ARB_shader_storage_buffer_object")) {
            caps.add(Caps.ShaderStorageBufferObject);
            limits.put(Limits.ShaderStorageBufferObjectMaxBlockSize,
                    getInteger(GL4.GL_MAX_SHADER_STORAGE_BLOCK_SIZE));
            // Commented out until we support ComputeShaders and the ComputeShader Cap
            // limits.put(Limits.ShaderStorageBufferObjectMaxComputeBlocks, getInteger(GL4.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS));
            if (caps.contains(Caps.GeometryShader)) {
                limits.put(Limits.ShaderStorageBufferObjectMaxGeometryBlocks,
                        getInteger(GL4.GL_MAX_GEOMETRY_SHADER_STORAGE_BLOCKS));
            }
            limits.put(Limits.ShaderStorageBufferObjectMaxFragmentBlocks,
                    getInteger(GL4.GL_MAX_FRAGMENT_SHADER_STORAGE_BLOCKS));
            limits.put(Limits.ShaderStorageBufferObjectMaxVertexBlocks,
                    getInteger(GL4.GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS));
            if (caps.contains(Caps.TesselationShader)) {
                limits.put(Limits.ShaderStorageBufferObjectMaxTessControlBlocks,
                        getInteger(GL4.GL_MAX_TESS_CONTROL_SHADER_STORAGE_BLOCKS));
                limits.put(Limits.ShaderStorageBufferObjectMaxTessEvaluationBlocks,
                        getInteger(GL4.GL_MAX_TESS_EVALUATION_SHADER_STORAGE_BLOCKS));
            }
            limits.put(Limits.ShaderStorageBufferObjectMaxCombineBlocks,
                    getInteger(GL4.GL_MAX_COMBINED_SHADER_STORAGE_BLOCKS));
        }

        if (hasExtension("GL_ARB_uniform_buffer_object")) {
            caps.add(Caps.UniformBufferObject);
            limits.put(Limits.UniformBufferObjectMaxBlockSize,
                    getInteger(GL3.GL_MAX_UNIFORM_BLOCK_SIZE));
            if (caps.contains(Caps.GeometryShader)) {
                limits.put(Limits.UniformBufferObjectMaxGeometryBlocks,
                        getInteger(GL3.GL_MAX_GEOMETRY_UNIFORM_BLOCKS));
            }
            limits.put(Limits.UniformBufferObjectMaxFragmentBlocks,
                    getInteger(GL3.GL_MAX_FRAGMENT_UNIFORM_BLOCKS));
            limits.put(Limits.UniformBufferObjectMaxVertexBlocks,
                    getInteger(GL3.GL_MAX_VERTEX_UNIFORM_BLOCKS));
        }

        if (caps.contains(Caps.OpenGL20)) {
            caps.add(Caps.UnpackRowLength);
        }

        if (caps.contains(Caps.OpenGL43) || hasExtension("GL_KHR_debug") || caps.contains(Caps.WebGL)) {
            caps.add(Caps.GLDebug);
        }

        // Print context information
        logger.log(Level.INFO, "OpenGL Renderer Information\n" +
                        " * Vendor: {0}\n" +
                        " * Renderer: {1}\n" +
                        " * OpenGL Version: {2}\n" +
                        " * GLSL Version: {3}\n" +
                        " * Profile: {4}",
                new Object[]{
                        gl.glGetString(GL.GL_VENDOR),
                        gl.glGetString(GL.GL_RENDERER),
                        gl.glGetString(GL.GL_VERSION),
                        gl.glGetString(GL.GL_SHADING_LANGUAGE_VERSION),
                        caps.contains(Caps.CoreProfile) ? "Core" : "Compatibility"
                });

        // Print capabilities (if fine logging is enabled)
        if (logger.isLoggable(Level.FINE)) {
            StringBuilder sb = new StringBuilder();
            sb.append("Supported capabilities: \n");
            for (Caps cap : caps)
            {
                sb.append("\t").append(cap.toString()).append("\n");
            }

            sb.append("\nHardware limits: \n");
            for (Limits limit : Limits.values()) {
                Integer value = limits.get(limit);
                if (value == null) {
                    value = 0;
                }
                sb.append("\t").append(limit.name()).append(" = ")
                  .append(value).append("\n");
            }

            logger.log(Level.FINE, sb.toString());
        }

        texUtil.initialize(caps);
    }

    private void loadCapabilities() {
        if (gl2 != null && !(gl instanceof GLES_30)) {
            loadCapabilitiesGL2();
        } else {
            loadCapabilitiesES();
        }
        loadCapabilitiesCommon();
    }

    private int getInteger(int en) {
        intBuf16.clear();
        gl.glGetInteger(en, intBuf16);
        return intBuf16.get(0);
    }

    private boolean getBoolean(int en) {
        gl.glGetBoolean(en, nameBuf);
        return nameBuf.get(0) != (byte)0;
    }

    @SuppressWarnings("fallthrough")
    @Override
    public void initialize() {
        loadCapabilities();

        // Initialize default state..
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);

        if (caps.contains(Caps.SeamlessCubemap)) {
            // Enable this globally. Should be OK.
            gl.glEnable(GLExt.GL_TEXTURE_CUBE_MAP_SEAMLESS);
        }

        if (caps.contains(Caps.CoreProfile)) {
            // Core Profile requires VAO to be bound.
            if(gl3!=null){
                gl3.glGenVertexArrays(intBuf16);
                int vaoId = intBuf16.get(0);
                gl3.glBindVertexArray(vaoId);
            }else if(gl instanceof GLES_30){
                ((GLES_30)gl).glGenVertexArrays(intBuf16);
                int vaoId = intBuf16.get(0);
                ((GLES_30)gl).glBindVertexArray(vaoId);                
            }   else{
                throw new UnsupportedOperationException("Core profile not supported");
            }
        }
        if (gl2 != null && !(gl instanceof GLES_30)) {
            gl2.glEnable(GL2.GL_VERTEX_PROGRAM_POINT_SIZE);
            if (!caps.contains(Caps.CoreProfile)) {
                gl2.glEnable(GL2.GL_POINT_SPRITE);
            }
        }

        IntBuffer tmp = BufferUtils.createIntBuffer(16);
        gl.glGetInteger(GL.GL_FRAMEBUFFER_BINDING, tmp);
        tmp.rewind();
        int fbOnLoad = tmp.get();
        if(fbOnLoad > 0)
        {
            // Override default FB to fbOnLoad. Mostly an iOS fix for scene processors and filters.
            defaultFBO = fbOnLoad;
        }
    }

    @Override
    public void invalidateState() {
        context.reset();
        if (gl2 != null) {
            context.initialDrawBuf = getInteger(GL2.GL_DRAW_BUFFER);
            context.initialReadBuf = getInteger(GL2.GL_READ_BUFFER);
        }
    }

    @Override
    public void resetGLObjects() {
        logger.log(Level.FINE, "Resetting objects and invalidating state");
        objManager.resetObjects();
        statistics.clearMemory();
        invalidateState();
    }

    @Override
    public void cleanup() {
        logger.log(Level.FINE, "Deleting objects and invalidating state");
        objManager.deleteAllObjects(this);
        OpenCLObjectManager.getInstance().deleteAllObjects();
        statistics.clearMemory();
        invalidateState();
    }

    /*********************************************************************\
     |* Render State                                                      *|
     \*********************************************************************/
    @Override
    public void setDepthRange(float start, float end) {
        gl.glDepthRange(start, end);
    }

    @Override
    public void clearBuffers(boolean color, boolean depth, boolean stencil) {
        int bits = 0;
        if (color) {
            //See explanations of the depth below, we must enable color write to be able to clear the color buffer
            if (context.colorWriteEnabled == false) {
                gl.glColorMask(true, true, true, true);
                context.colorWriteEnabled = true;
            }
            bits = GL.GL_COLOR_BUFFER_BIT;
        }
        if (depth) {
            // glClear(GL.GL_DEPTH_BUFFER_BIT) seems to not work when glDepthMask is false.
            // Here is a link to the openGL discussion:
            // http://www.opengl.org/discussion_boards/ubbthreads.php?ubb=showflat&Number=257223
            // If depth clear is requested, we enable the depth mask.
            if (context.depthWriteEnabled == false) {
                gl.glDepthMask(true);
                context.depthWriteEnabled = true;
            }
            bits |= GL.GL_DEPTH_BUFFER_BIT;
        }
        if (stencil) {
            // May need to set glStencilMask(0xFF) here if we ever allow users
            // to change the stencil mask.
            bits |= GL.GL_STENCIL_BUFFER_BIT;
        }
        if (bits != 0) {
            gl.glClear(bits);
        }
    }

    @Override
    public void setBackgroundColor(ColorRGBA color) {
        if (!context.clearColor.equals(color)) {
            gl.glClearColor(color.r, color.g, color.b, color.a);
            context.clearColor.set(color);
        }
    }

    @Override
    public void setDefaultAnisotropicFilter(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level cannot be less than 1");
        }
        this.defaultAnisotropicFilter = level;
    }

    @Override
    public void setAlphaToCoverage(boolean value) {
        if (caps.contains(Caps.Multisample)) {
            if (value) {
                gl.glEnable(GLExt.GL_SAMPLE_ALPHA_TO_COVERAGE_ARB);
            } else {
                gl.glDisable(GLExt.GL_SAMPLE_ALPHA_TO_COVERAGE_ARB);
            }
        }
    }

    @Override
    public void applyRenderState(RenderState state) {
        if (gl2 != null) {
            if (state.isWireframe() && !context.wireframe) {
                gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2.GL_LINE);
                context.wireframe = true;
            } else if (!state.isWireframe() && context.wireframe) {
                gl2.glPolygonMode(GL.GL_FRONT_AND_BACK, GL2.GL_FILL);
                context.wireframe = false;
            }
        }

        if (state.isDepthTest() && !context.depthTestEnabled) {
            gl.glEnable(GL.GL_DEPTH_TEST);
            context.depthTestEnabled = true;
        } else if (!state.isDepthTest() && context.depthTestEnabled) {
            gl.glDisable(GL.GL_DEPTH_TEST);
            context.depthTestEnabled = false;
        }
        if (state.isDepthTest() && state.getDepthFunc() != context.depthFunc) {
            gl.glDepthFunc(convertTestFunction(state.getDepthFunc()));
            context.depthFunc = state.getDepthFunc();
        }

        if (state.isDepthWrite() && !context.depthWriteEnabled) {
            gl.glDepthMask(true);
            context.depthWriteEnabled = true;
        } else if (!state.isDepthWrite() && context.depthWriteEnabled) {
            gl.glDepthMask(false);
            context.depthWriteEnabled = false;
        }

        if (state.isColorWrite() && !context.colorWriteEnabled) {
            gl.glColorMask(true, true, true, true);
            context.colorWriteEnabled = true;
        } else if (!state.isColorWrite() && context.colorWriteEnabled) {
            gl.glColorMask(false, false, false, false);
            context.colorWriteEnabled = false;
        }

        if (state.isPolyOffset()) {
            if (!context.polyOffsetEnabled) {
                gl.glEnable(GL.GL_POLYGON_OFFSET_FILL);
                gl.glPolygonOffset(state.getPolyOffsetFactor(),
                        state.getPolyOffsetUnits());
                context.polyOffsetEnabled = true;
                context.polyOffsetFactor = state.getPolyOffsetFactor();
                context.polyOffsetUnits = state.getPolyOffsetUnits();
            } else {
                if (state.getPolyOffsetFactor() != context.polyOffsetFactor
                        || state.getPolyOffsetUnits() != context.polyOffsetUnits) {
                    gl.glPolygonOffset(state.getPolyOffsetFactor(),
                            state.getPolyOffsetUnits());
                    context.polyOffsetFactor = state.getPolyOffsetFactor();
                    context.polyOffsetUnits = state.getPolyOffsetUnits();
                }
            }
        } else {
            if (context.polyOffsetEnabled) {
                gl.glDisable(GL.GL_POLYGON_OFFSET_FILL);
                context.polyOffsetEnabled = false;
                context.polyOffsetFactor = 0;
                context.polyOffsetUnits = 0;
            }
        }

        if (state.getFaceCullMode() != context.cullMode) {
            if (state.getFaceCullMode() == RenderState.FaceCullMode.Off) {
                gl.glDisable(GL.GL_CULL_FACE);
            } else {
                gl.glEnable(GL.GL_CULL_FACE);
            }

            switch (state.getFaceCullMode()) {
                case Off:
                    break;
                case Back:
                    gl.glCullFace(GL.GL_BACK);
                    break;
                case Front:
                    gl.glCullFace(GL.GL_FRONT);
                    break;
                case FrontAndBack:
                    gl.glCullFace(GL.GL_FRONT_AND_BACK);
                    break;
                default:
                    throw new UnsupportedOperationException("Unrecognized face cull mode: "
                            + state.getFaceCullMode());
            }

            context.cullMode = state.getFaceCullMode();
        }

        // Always update the blend equations and factors when using custom blend mode.
        if (state.getBlendMode() == BlendMode.Custom) {
            changeBlendMode(BlendMode.Custom);

            blendFuncSeparate(
                    state.getCustomSfactorRGB(),
                    state.getCustomDfactorRGB(),
                    state.getCustomSfactorAlpha(),
                    state.getCustomDfactorAlpha());
            blendEquationSeparate(state.getBlendEquation(), state.getBlendEquationAlpha());

        // Update the blend equations and factors only on a mode change for all the other (common) blend modes.
        } else if (state.getBlendMode() != context.blendMode) {
            changeBlendMode(state.getBlendMode());

            switch (state.getBlendMode()) {
                case Off:
                    break;
                case Additive:
                    blendFunc(RenderState.BlendFunc.One, RenderState.BlendFunc.One);
                    break;
                case AlphaAdditive:
                    blendFunc(RenderState.BlendFunc.Src_Alpha, RenderState.BlendFunc.One);
                    break;
                case Alpha:
                    blendFunc(RenderState.BlendFunc.Src_Alpha, RenderState.BlendFunc.One_Minus_Src_Alpha);
                    break;
                case AlphaSumA:
                    blendFuncSeparate(
                        RenderState.BlendFunc.Src_Alpha,
                        RenderState.BlendFunc.One_Minus_Src_Alpha,
                        RenderState.BlendFunc.One,
                        RenderState.BlendFunc.One
                    );
                    break;
                case PremultAlpha:
                    blendFunc(RenderState.BlendFunc.One, RenderState.BlendFunc.One_Minus_Src_Alpha);
                    break;
                case Modulate:
                    blendFunc(RenderState.BlendFunc.Dst_Color, RenderState.BlendFunc.Zero);
                    break;
                case ModulateX2:
                    blendFunc(RenderState.BlendFunc.Dst_Color, RenderState.BlendFunc.Src_Color);
                    break;
                case Color:
                case Screen:
                    blendFunc(RenderState.BlendFunc.One, RenderState.BlendFunc.One_Minus_Src_Color);
                    break;
                case Exclusion:
                    blendFunc(RenderState.BlendFunc.One_Minus_Dst_Color, RenderState.BlendFunc.One_Minus_Src_Color);
                    break;
                default:
                    throw new UnsupportedOperationException("Unrecognized blend mode: "
                            + state.getBlendMode());
            }

            // All of the common modes require the ADD equation.
            // (This might change in the future?)
            blendEquationSeparate(RenderState.BlendEquation.Add, RenderState.BlendEquationAlpha.InheritColor);
        }

        if (context.stencilTest != state.isStencilTest()
                || context.frontStencilStencilFailOperation != state.getFrontStencilStencilFailOperation()
                || context.frontStencilDepthFailOperation != state.getFrontStencilDepthFailOperation()
                || context.frontStencilDepthPassOperation != state.getFrontStencilDepthPassOperation()
                || context.backStencilStencilFailOperation != state.getBackStencilStencilFailOperation()
                || context.backStencilDepthFailOperation != state.getBackStencilDepthFailOperation()
                || context.backStencilDepthPassOperation != state.getBackStencilDepthPassOperation()
                || context.frontStencilFunction != state.getFrontStencilFunction()
                || context.backStencilFunction != state.getBackStencilFunction()) {

            context.frontStencilStencilFailOperation = state.getFrontStencilStencilFailOperation();   //terrible looking, I know
            context.frontStencilDepthFailOperation = state.getFrontStencilDepthFailOperation();
            context.frontStencilDepthPassOperation = state.getFrontStencilDepthPassOperation();
            context.backStencilStencilFailOperation = state.getBackStencilStencilFailOperation();
            context.backStencilDepthFailOperation = state.getBackStencilDepthFailOperation();
            context.backStencilDepthPassOperation = state.getBackStencilDepthPassOperation();
            context.frontStencilFunction = state.getFrontStencilFunction();
            context.backStencilFunction = state.getBackStencilFunction();

            if (state.isStencilTest()) {
                gl.glEnable(GL.GL_STENCIL_TEST);
                gl.glStencilOpSeparate(GL.GL_FRONT,
                        convertStencilOperation(state.getFrontStencilStencilFailOperation()),
                        convertStencilOperation(state.getFrontStencilDepthFailOperation()),
                        convertStencilOperation(state.getFrontStencilDepthPassOperation()));
                gl.glStencilOpSeparate(GL.GL_BACK,
                        convertStencilOperation(state.getBackStencilStencilFailOperation()),
                        convertStencilOperation(state.getBackStencilDepthFailOperation()),
                        convertStencilOperation(state.getBackStencilDepthPassOperation()));
                gl.glStencilFuncSeparate(GL.GL_FRONT,
                        convertTestFunction(state.getFrontStencilFunction()),
                        state.getFrontStencilReference(), state.getFrontStencilMask());
                gl.glStencilFuncSeparate(GL.GL_BACK,
                        convertTestFunction(state.getBackStencilFunction()),
                        state.getBackStencilReference(), state.getBackStencilMask());
            } else {
                gl.glDisable(GL.GL_STENCIL_TEST);
            }
        }
        if (context.lineWidth != state.getLineWidth()) {
            gl.glLineWidth(state.getLineWidth());
            context.lineWidth = state.getLineWidth();
        }
    }

    private void changeBlendMode(RenderState.BlendMode blendMode) {
        if (blendMode != context.blendMode) {
            if (blendMode == RenderState.BlendMode.Off) {
                gl.glDisable(GL.GL_BLEND);
            } else if (context.blendMode == RenderState.BlendMode.Off) {
                gl.glEnable(GL.GL_BLEND);
            }

            context.blendMode = blendMode;
        }
    }

    private void blendEquationSeparate(RenderState.BlendEquation blendEquation, RenderState.BlendEquationAlpha blendEquationAlpha) {
        if (blendEquation != context.blendEquation || blendEquationAlpha != context.blendEquationAlpha) {
            int glBlendEquation = convertBlendEquation(blendEquation);
            int glBlendEquationAlpha = blendEquationAlpha == RenderState.BlendEquationAlpha.InheritColor
                    ? glBlendEquation
                    : convertBlendEquationAlpha(blendEquationAlpha);
            gl.glBlendEquationSeparate(glBlendEquation, glBlendEquationAlpha);
            context.blendEquation = blendEquation;
            context.blendEquationAlpha = blendEquationAlpha;
        }
    }

    private void blendFunc(RenderState.BlendFunc sfactor, RenderState.BlendFunc dfactor) {
        if (sfactor != context.sfactorRGB
                || dfactor != context.dfactorRGB
                || sfactor != context.sfactorAlpha
                || dfactor != context.dfactorAlpha) {

            gl.glBlendFunc(
                    convertBlendFunc(sfactor),
                    convertBlendFunc(dfactor));
            context.sfactorRGB = sfactor;
            context.dfactorRGB = dfactor;
            context.sfactorAlpha = sfactor;
            context.dfactorAlpha = dfactor;
        }
    }

    private void blendFuncSeparate(RenderState.BlendFunc sfactorRGB, RenderState.BlendFunc dfactorRGB,
            RenderState.BlendFunc sfactorAlpha, RenderState.BlendFunc dfactorAlpha) {
        if (sfactorRGB != context.sfactorRGB
                || dfactorRGB != context.dfactorRGB
                || sfactorAlpha != context.sfactorAlpha
                || dfactorAlpha != context.dfactorAlpha) {

            gl.glBlendFuncSeparate(
                    convertBlendFunc(sfactorRGB),
                    convertBlendFunc(dfactorRGB),
                    convertBlendFunc(sfactorAlpha),
                    convertBlendFunc(dfactorAlpha));
            context.sfactorRGB = sfactorRGB;
            context.dfactorRGB = dfactorRGB;
            context.sfactorAlpha = sfactorAlpha;
            context.dfactorAlpha = dfactorAlpha;
        }
    }

    private int convertBlendEquation(RenderState.BlendEquation blendEquation) {
        switch (blendEquation) {
            case Add:
                return GL2.GL_FUNC_ADD;
            case Subtract:
                return GL2.GL_FUNC_SUBTRACT;
            case ReverseSubtract:
                return GL2.GL_FUNC_REVERSE_SUBTRACT;
            case Min:
                return GL2.GL_MIN;
            case Max:
                return GL2.GL_MAX;
            default:
                throw new UnsupportedOperationException("Unrecognized blend operation: " + blendEquation);
        }
    }

    private int convertBlendEquationAlpha(RenderState.BlendEquationAlpha blendEquationAlpha) {
        //Note: InheritColor mode should already be handled, that is why it does not belong the switch case.
        switch (blendEquationAlpha) {
            case Add:
                return GL2.GL_FUNC_ADD;
            case Subtract:
                return GL2.GL_FUNC_SUBTRACT;
            case ReverseSubtract:
                return GL2.GL_FUNC_REVERSE_SUBTRACT;
            case Min:
                return GL2.GL_MIN;
            case Max:
                return GL2.GL_MAX;
            default:
                throw new UnsupportedOperationException("Unrecognized alpha blend operation: " + blendEquationAlpha);
        }
    }

    private int convertBlendFunc(BlendFunc blendFunc) {
        switch (blendFunc) {
            case Zero:
                return GL.GL_ZERO;
            case One:
                return GL.GL_ONE;
            case Src_Color:
                return GL.GL_SRC_COLOR;
            case One_Minus_Src_Color:
                return GL.GL_ONE_MINUS_SRC_COLOR;
            case Dst_Color:
                return GL.GL_DST_COLOR;
            case One_Minus_Dst_Color:
                return GL.GL_ONE_MINUS_DST_COLOR;
            case Src_Alpha:
                return GL.GL_SRC_ALPHA;
            case One_Minus_Src_Alpha:
                return GL.GL_ONE_MINUS_SRC_ALPHA;
            case Dst_Alpha:
                return GL.GL_DST_ALPHA;
            case One_Minus_Dst_Alpha:
                return GL.GL_ONE_MINUS_DST_ALPHA;
            case Src_Alpha_Saturate:
                return GL.GL_SRC_ALPHA_SATURATE;
            default:
                throw new UnsupportedOperationException("Unrecognized blend function operation: " + blendFunc);
         }
    }

    private int convertStencilOperation(StencilOperation stencilOp) {
        switch (stencilOp) {
            case Keep:
                return GL.GL_KEEP;
            case Zero:
                return GL.GL_ZERO;
            case Replace:
                return GL.GL_REPLACE;
            case Increment:
                return GL.GL_INCR;
            case IncrementWrap:
                return GL.GL_INCR_WRAP;
            case Decrement:
                return GL.GL_DECR;
            case DecrementWrap:
                return GL.GL_DECR_WRAP;
            case Invert:
                return GL.GL_INVERT;
            default:
                throw new UnsupportedOperationException("Unrecognized stencil operation: " + stencilOp);
        }
    }

    private int convertTestFunction(TestFunction testFunc) {
        switch (testFunc) {
            case Never:
                return GL.GL_NEVER;
            case Less:
                return GL.GL_LESS;
            case LessOrEqual:
                return GL.GL_LEQUAL;
            case Greater:
                return GL.GL_GREATER;
            case GreaterOrEqual:
                return GL.GL_GEQUAL;
            case Equal:
                return GL.GL_EQUAL;
            case NotEqual:
                return GL.GL_NOTEQUAL;
            case Always:
                return GL.GL_ALWAYS;
            default:
                throw new UnsupportedOperationException("Unrecognized test function: " + testFunc);
        }
    }

    /*********************************************************************\
     |* Camera and World transforms                                       *|
     \*********************************************************************/
    @Override
    public void setViewPort(int x, int y, int w, int h) {
        if (x != vpX || vpY != y || vpW != w || vpH != h) {
            gl.glViewport(x, y, w, h);
            vpX = x;
            vpY = y;
            vpW = w;
            vpH = h;
        }
    }

    @Override
    public void setClipRect(int x, int y, int width, int height) {
        if (!context.clipRectEnabled) {
            gl.glEnable(GL.GL_SCISSOR_TEST);
            context.clipRectEnabled = true;
        }
        if (clipX != x || clipY != y || clipW != width || clipH != height) {
            gl.glScissor(x, y, width, height);
            clipX = x;
            clipY = y;
            clipW = width;
            clipH = height;
        }
    }

    @Override
    public void clearClipRect() {
        if (context.clipRectEnabled) {
            gl.glDisable(GL.GL_SCISSOR_TEST);
            context.clipRectEnabled = false;

            clipX = 0;
            clipY = 0;
            clipW = 0;
            clipH = 0;
        }
    }

    @Override
    public void postFrame() {
        objManager.deleteUnused(this);
        OpenCLObjectManager.getInstance().deleteUnusedObjects();
        gl.resetStats();
    }

    protected void bindProgram(Shader shader) {
        int shaderId = shader.getId();
        if (context.boundShaderProgram != shaderId) {
            gl.glUseProgram(shaderId);
            statistics.onShaderUse(shader, true);
            context.boundShader = shader;
            context.boundShaderProgram = shaderId;
        } else {
            statistics.onShaderUse(shader, false);
        }
    }

    /*=========*\
    |* Shaders *|
    \*=========*/

    /**
     * Update the location of the specified Uniform in the specified Shader.
     *
     * @param shader the Shader containing the Uniform (not null)
     * @param uniform the Uniform to update (not null)
     */
    protected void updateUniformLocation(Shader shader, Uniform uniform) {
        int loc = gl.glGetUniformLocation(shader.getId(), uniform.getName());
        if (loc < 0) {
            uniform.setLocation(-1);
            // uniform is not declared in shader
            if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "Uniform {0} is not declared in shader {1}.",
                        new Object[]{uniform.getName(), shader.getSources()});
            }
        } else {
            uniform.setLocation(loc);
        }
    }

    private boolean isValidNumber(float v) {
        return !Float.isNaN(v);
    }

    private boolean isValidNumber(FloatBuffer fb) {
        for(int i = 0; i < fb.limit(); i++) {
            if (!isValidNumber(fb.get(i))) return false;
        }
        return true;
    }
    
    private boolean isValidNumber(Vector2f v) {
        return isValidNumber(v.x) && isValidNumber(v.y);
    }

    private boolean isValidNumber(Vector3f v) {
        return isValidNumber(v.x) && isValidNumber(v.y) && isValidNumber(v.z);
    }

    private boolean isValidNumber(Quaternion q) {
        return isValidNumber(q.getX()) && isValidNumber(q.getY()) && isValidNumber(q.getZ()) && isValidNumber(q.getW());
    }

    private boolean isValidNumber(ColorRGBA c) {
        return isValidNumber(c.r) && isValidNumber(c.g) && isValidNumber(c.b) && isValidNumber(c.a);
    }

    private boolean isValidNumber(Vector4f c) {
        return isValidNumber(c.x) && isValidNumber(c.y) && isValidNumber(c.z) && isValidNumber(c.w);
    }

    protected void updateUniform(Shader shader, Uniform uniform) {
        int shaderId = shader.getId();

        assert uniform.getName() != null;
        assert shader.getId() > 0;

        bindProgram(shader);

        int loc = uniform.getLocation();
        if (loc == -1) {
            return;
        }

        if (loc == -2) {
            // get uniform location
            updateUniformLocation(shader, uniform);
            if (uniform.getLocation() == -1) {
                // not declared, ignore
                uniform.clearUpdateNeeded();
                return;
            }
            loc = uniform.getLocation();
        }

        if (uniform.getVarType() == null) {
            return; // value not set yet
        }
        statistics.onUniformSet();

        uniform.clearUpdateNeeded();
        FloatBuffer fb;
        IntBuffer ib;
        switch (uniform.getVarType()) {
            case Float:
                Float f = (Float) uniform.getValue();
                assert isValidNumber(f) : "Invalid float value " + f + " for " + uniform.getBinding();
                gl.glUniform1f(loc, f.floatValue());
                break;
            case Vector2:
                Vector2f v2 = (Vector2f) uniform.getValue();
                assert isValidNumber(v2) : "Invalid Vector2f value " + v2 + " for " + uniform.getBinding();
                gl.glUniform2f(loc, v2.getX(), v2.getY());
                break;
            case Vector3:
                Vector3f v3 = (Vector3f) uniform.getValue();
                assert isValidNumber(v3) : "Invalid Vector3f value " + v3 + " for " + uniform.getBinding();
                gl.glUniform3f(loc, v3.getX(), v3.getY(), v3.getZ());
                break;
            case Vector4:
                Object val = uniform.getValue();
                if (val instanceof ColorRGBA) {
                    ColorRGBA c = (ColorRGBA) val;
                    assert isValidNumber(c) : "Invalid ColorRGBA value " + c + " for " + uniform.getBinding();
                    gl.glUniform4f(loc, c.r, c.g, c.b, c.a);
                } else if (val instanceof Vector4f) {
                    Vector4f c = (Vector4f) val;
                    assert isValidNumber(c) : "Invalid Vector4f value " + c + " for " + uniform.getBinding();
                    gl.glUniform4f(loc, c.x, c.y, c.z, c.w);
                } else {
                    Quaternion c = (Quaternion) uniform.getValue();
                    assert isValidNumber(c) : "Invalid Quaternion value " + c + " for "
                            + uniform.getBinding();
                    gl.glUniform4f(loc, c.getX(), c.getY(), c.getZ(), c.getW());
                }
                break;
            case Boolean:
                Boolean b = (Boolean) uniform.getValue();
                gl.glUniform1i(loc, b.booleanValue() ? GL.GL_TRUE : GL.GL_FALSE);
                break;
            case Matrix3:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Matrix3f value " + uniform.getValue() + " for "
                        + uniform.getBinding();
                assert fb.remaining() == 9;
                gl.glUniformMatrix3(loc, false, fb);
                break;
            case Matrix4:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Matrix4f value " + uniform.getValue() + " for "
                        + uniform.getBinding();
                assert fb.remaining() == 16;
                gl.glUniformMatrix4(loc, false, fb);
                break;
            case IntArray:
                ib = (IntBuffer) uniform.getValue();
                gl.glUniform1(loc, ib);
                break;
            case FloatArray:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid float array value "
                        + Arrays.asList((float[]) uniform.getValue()) + " for " + uniform.getBinding();
                gl.glUniform1(loc, fb);
                break;
            case Vector2Array:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Vector2f array value "
                        + Arrays.deepToString((Vector2f[]) uniform.getValue()) + " for "
                        + uniform.getBinding();
                gl.glUniform2(loc, fb);
                break;
            case Vector3Array:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Vector3f array value "
                        + Arrays.deepToString((Vector3f[]) uniform.getValue()) + " for "
                        + uniform.getBinding();
                gl.glUniform3(loc, fb);
                break;
            case Vector4Array:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Vector4f array value "
                        + Arrays.deepToString((Vector4f[]) uniform.getValue()) + " for "
                        + uniform.getBinding();
                gl.glUniform4(loc, fb);
                break;
            case Matrix4Array:
                fb = uniform.getMultiData();
                assert isValidNumber(fb) : "Invalid Matrix4f array value "
                        + Arrays.deepToString((Matrix4f[]) uniform.getValue()) + " for "
                        + uniform.getBinding();
                gl.glUniformMatrix4(loc, false, fb);
                break;
            case Int:
                Integer i = (Integer) uniform.getValue();
                gl.glUniform1i(loc, i.intValue());
                break;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported uniform type: " + uniform.getVarType() + " for " + uniform.getBinding());
        }
    }

    /**
     * Updates the buffer block for the shader.
     *
     * @param shader the shader.
     * @param bufferBlock the storage block.
     */
    protected void updateShaderBufferBlock(final Shader shader, final ShaderBufferBlock bufferBlock) {

        assert bufferBlock.getName() != null;
        assert shader.getId() > 0;

        final BufferObject bufferObject = bufferBlock.getBufferObject();
        final BufferType bufferType = bufferBlock.getType();
        

        if (bufferObject.isUpdateNeeded()) {
            if (bufferType == BufferType.ShaderStorageBufferObject) {
                updateShaderStorageBufferObjectData(bufferObject);
            } else {
                updateUniformBufferObjectData(bufferObject);
            }
        }

        int usage = resolveUsageHint(bufferObject.getAccessHint(), bufferObject.getNatureHint());
        if (usage == -1) return; // cpu only

        bindProgram(shader);

        final int shaderId = shader.getId();

        int bindingPoint = bufferObject.getBinding();

        switch (bufferType) {
            case UniformBufferObject: {
                setUniformBufferObject(bindingPoint, bufferObject); // rebind buffer if needed
                if (bufferBlock.isUpdateNeeded()) {
                    int blockIndex = bufferBlock.getLocation();
                    if (blockIndex < 0) {
                        blockIndex = gl3.glGetUniformBlockIndex(shaderId, bufferBlock.getName());
                        bufferBlock.setLocation(blockIndex);
                    }
                    if (bufferBlock.getLocation() != NativeObject.INVALID_ID) {
                        gl3.glUniformBlockBinding(shaderId, bufferBlock.getLocation(), bindingPoint);
                    } 
                }
                break;
            }
            case ShaderStorageBufferObject: {
                setShaderStorageBufferObject(bindingPoint, bufferObject); // rebind buffer if needed
                if (bufferBlock.isUpdateNeeded() ) {
                    int blockIndex = bufferBlock.getLocation();
                    if (blockIndex < 0) {
                        blockIndex = gl4.glGetProgramResourceIndex(shaderId, GL4.GL_SHADER_STORAGE_BLOCK, bufferBlock.getName());
                        bufferBlock.setLocation(blockIndex);
                    }
                    if (bufferBlock.getLocation() != NativeObject.INVALID_ID) {
                        gl4.glShaderStorageBlockBinding(shaderId, bufferBlock.getLocation(), bindingPoint);
                    }
                }
                break;
            }
            default: {
                throw new IllegalArgumentException("Doesn't support binding of " + bufferType);
            }
        }

        bufferBlock.clearUpdateNeeded();
    }

    protected void updateShaderUniforms(Shader shader) {
        ListMap<String, Uniform> uniforms = shader.getUniformMap();
        for (int i = 0; i < uniforms.size(); i++) {
            Uniform uniform = uniforms.getValue(i);
            if (uniform.isUpdateNeeded()) {
                updateUniform(shader, uniform);
            }
        }
    }

    /**
     * Updates all shader's buffer blocks.
     *
     * @param shader the shader.
     */
    protected void updateShaderBufferBlocks(final Shader shader) {
        final ListMap<String, ShaderBufferBlock> bufferBlocks = shader.getBufferBlockMap();
        for (int i = 0; i < bufferBlocks.size(); i++) {
            updateShaderBufferBlock(shader, bufferBlocks.getValue(i));
        }
    }

    protected void resetUniformLocations(Shader shader) {
        ListMap<String, Uniform> uniforms = shader.getUniformMap();
        for (int i = 0; i < uniforms.size(); i++) {
            Uniform uniform = uniforms.getValue(i);
            uniform.reset(); // e.g check location again
        }
    }

    public int convertShaderType(ShaderType type) {
        switch (type) {
            case Fragment:
                return GL.GL_FRAGMENT_SHADER;
            case Vertex:
                return GL.GL_VERTEX_SHADER;
            case Geometry:
                return GL3.GL_GEOMETRY_SHADER;
            case TessellationControl:
                return GL4.GL_TESS_CONTROL_SHADER;
            case TessellationEvaluation:
                return GL4.GL_TESS_EVALUATION_SHADER;
            default:
                throw new UnsupportedOperationException("Unrecognized shader type.");
        }
    }

    public void updateShaderSourceData(ShaderSource source) {
        int id = source.getId();
        if (id == -1) {
            // Create id
            id = gl.glCreateShader(convertShaderType(source.getType()));
            if (id <= 0) {
                throw new RendererException("Invalid ID received when trying to create shader.");
            }

            source.setId(id);
            if (debug && caps.contains(Caps.GLDebug)) {
                if(source.getName()!=null) glext.glObjectLabel(GLExt.GL_SHADER, id, source.getName());
            }
        } else {
            throw new RendererException("Cannot recompile shader source");
        }

        boolean gles3 = caps.contains(Caps.OpenGLES30);
        boolean gles2 = caps.contains(Caps.OpenGLES20);
        String language = source.getLanguage();

        if (!gles3 && gles2 && !language.equals("GLSL100")) { //avoid this check for gles3
            throw new RendererException("This shader cannot run in OpenGL ES 2. "
                    + "Only GLSL 1.00 shaders are supported.");
        }

        // Upload shader source.
        // Merge the defines and source code.
        stringBuf.setLength(0);
        int version = Integer.parseInt(language.substring(4));
        if (language.startsWith("GLSL")) {
            if (version > 100) {
                stringBuf.append("#version ");
                stringBuf.append(language.substring(4));
                if (version >= 150) {
                    if(gles3) {
                        stringBuf.append(" es");
                    }
                    else {
                        stringBuf.append(" core");
                    }
                }
                stringBuf.append("\n");
            } else {
                if (gles2 || gles3) {
                    // request GLSL ES (1.00) when compiling under GLES2.
                    stringBuf.append("#version 100\n");

                } else {
                    // version 100 does not exist in desktop GLSL.
                    // put version 110 in that case to enable strict checking
                    // (Only enabled for desktop GL)
                    stringBuf.append("#version 110\n");
                }
            }

        }
        
        if (linearizeSrgbImages) {
            stringBuf.append("#define SRGB 1\n");
        }
        stringBuf.append("#define ").append(source.getType().name().toUpperCase()).append("_SHADER 1\n");

        stringBuf.append(source.getDefines());
        stringBuf.append(source.getSource());


        intBuf1.clear();
        intBuf1.put(0, stringBuf.length());
        gl.glShaderSource(id, new String[]{ stringBuf.toString() }, intBuf1);
        gl.glCompileShader(id);

        gl.glGetShader(id, GL.GL_COMPILE_STATUS, intBuf1);

        boolean compiledOK = intBuf1.get(0) == GL.GL_TRUE;
        String infoLog = null;

        if (VALIDATE_SHADER || !compiledOK) {
            // even if compile succeeded, check
            // log for warnings
            gl.glGetShader(id, GL.GL_INFO_LOG_LENGTH, intBuf1);
            int length = intBuf1.get(0);
            if (length > 3) {
                // get infos
                infoLog = gl.glGetShaderInfoLog(id, length);
            }
        }

        if (compiledOK) {
            if (infoLog != null) {
                logger.log(Level.WARNING, "{0} compiled successfully, compiler warnings: \n{1}",
                        new Object[]{source.getName(), infoLog});
            } else if (logger.isLoggable(Level.FINE)) {
                logger.log(Level.FINE, "{0} compiled successfully.", source.getName());
            }
            source.clearUpdateNeeded();
        } else {
            logger.log(Level.WARNING, "Bad compile of:\n{0}",
                    new Object[]{ShaderDebug.formatShaderSource(stringBuf.toString())});
            if (infoLog != null) {
                throw new RendererException("compile error in: " + source + "\n" + infoLog);
            } else {
                throw new RendererException("compile error in: " + source + "\nerror: <not provided>");
            }
        }
    }

    public void updateShaderData(Shader shader) {
        int id = shader.getId();
        boolean needRegister = false;
        if (id == -1) {
            // create program
            id = gl.glCreateProgram();
            if (id == 0) {
                throw new RendererException("Invalid ID (" + id + ") received when trying to create shader program.");
            }

            shader.setId(id);
            needRegister = true;
        }

        // If using GLSL 1.5, we bind the outputs for the user
        // For versions 3.3 and up, user should use layout qualifiers instead.
        boolean bindFragDataRequired = false;

        for (ShaderSource source : shader.getSources()) {
            if (source.isUpdateNeeded()) {
                updateShaderSourceData(source);
            }
            if (source.getType() == ShaderType.Fragment
                    && source.getLanguage().equals("GLSL150")) {
                bindFragDataRequired = true;
            }
            gl.glAttachShader(id, source.getId());
        }

        if (bindFragDataRequired) {
            // Check if GLSL version is 1.5 for shader
            gl3.glBindFragDataLocation(id, 0, "outFragColor");
            // For MRT
            for (int i = 0; i < limits.get(Limits.FrameBufferMrtAttachments); i++) {
                gl3.glBindFragDataLocation(id, i, "outFragData[" + i + "]");
            }
        }

        // Link shaders to program
        gl.glLinkProgram(id);

        // Check link status
        gl.glGetProgram(id, GL.GL_LINK_STATUS, intBuf1);
        boolean linkOK = intBuf1.get(0) == GL.GL_TRUE;
        String infoLog = null;

        if (VALIDATE_SHADER || !linkOK) {
            gl.glGetProgram(id, GL.GL_INFO_LOG_LENGTH, intBuf1);
            int length = intBuf1.get(0);
            if (length > 3) {
                // get infos
                infoLog = gl.glGetProgramInfoLog(id, length);
            }
        }

        if (linkOK) {
            if (infoLog != null) {
                logger.log(Level.WARNING, "Shader linked successfully. Linker warnings: \n{0}", infoLog);
            } else {
                logger.fine("Shader linked successfully.");
            }
            shader.clearUpdateNeeded();
            if (needRegister) {
                // Register shader for clean up if it was created in this method.
                objManager.registerObject(shader);
                statistics.onNewShader();
            } else {
                // OpenGL spec: uniform locations may change after re-link
                resetUniformLocations(shader);
            }
        } else {
            if (infoLog != null) {
                throw new RendererException("Shader failed to link, shader:" + shader + "\n" + infoLog);
            } else {
                throw new RendererException("Shader failed to link, shader:" + shader + "\ninfo: <not provided>");
            }
        }
    }

    @Override
    public void setShader(Shader shader) {
        if (shader == null) {
            throw new IllegalArgumentException("Shader cannot be null");
        } else {            
            if (shader.isUpdateNeeded()) {
                updateShaderData(shader);
            }

            // NOTE: might want to check if any of the
            // sources need an update?

            assert shader.getId() > 0;

            updateShaderUniforms(shader);
            updateShaderBufferBlocks(shader);
            bindProgram(shader);
        }
    }

    @Override
    public void deleteShaderSource(ShaderSource source) {
        if (source.getId() < 0) {
            logger.warning("Shader source is not uploaded to GPU, cannot delete.");
            return;
        }
        source.clearUpdateNeeded();
        gl.glDeleteShader(source.getId());
        source.resetObject();
    }

    @Override
    public void deleteShader(Shader shader) {
        if (shader.getId() == -1) {
            logger.warning("Shader is not uploaded to GPU, cannot delete.");
            return;
        }

        for (ShaderSource source : shader.getSources()) {
            if (source.getId() != -1) {
                gl.glDetachShader(shader.getId(), source.getId());
                deleteShaderSource(source);
            }
        }

        gl.glDeleteProgram(shader.getId());
        statistics.onDeleteShader();
        shader.resetObject();
    }

    /*==============*\
    |* Framebuffers *|
    \*==============*/

    /**
     * Copy the source buffer to the destination buffer, including both color
     * and depth.
     *
     * @param src the source buffer (unaffected)
     * @param dst the destination buffer
     */
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst) {
        copyFrameBuffer(src, dst, true, true);
    }

    @Override
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyDepth) {
        copyFrameBuffer(src, dst, true, copyDepth);
    }

    @Override
    public void copyFrameBuffer(FrameBuffer src, FrameBuffer dst, boolean copyColor,boolean copyDepth) {
        if (caps.contains(Caps.FrameBufferBlit)) {
            int srcX0 = 0;
            int srcY0 = 0;
            int srcX1;
            int srcY1;

            int dstX0 = 0;
            int dstY0 = 0;
            int dstX1;
            int dstY1;

            int prevFBO = context.boundFBO;

            if (mainFbOverride != null) {
                if (src == null) {
                    src = mainFbOverride;
                }
                if (dst == null) {
                    dst = mainFbOverride;
                }
            }

            if (src != null && src.isUpdateNeeded()) {
                updateFrameBuffer(src);
            }

            if (dst != null && dst.isUpdateNeeded()) {
                updateFrameBuffer(dst);
            }

            if (src == null) {
                glfbo.glBindFramebufferEXT(GLFbo.GL_READ_FRAMEBUFFER_EXT, 0);
                srcX0 = vpX;
                srcY0 = vpY;
                srcX1 = vpX + vpW;
                srcY1 = vpY + vpH;
            } else {
                glfbo.glBindFramebufferEXT(GLFbo.GL_READ_FRAMEBUFFER_EXT, src.getId());
                srcX1 = src.getWidth();
                srcY1 = src.getHeight();
            }
            if (dst == null) {
                glfbo.glBindFramebufferEXT(GLFbo.GL_DRAW_FRAMEBUFFER_EXT, 0);
                dstX0 = vpX;
                dstY0 = vpY;
                dstX1 = vpX + vpW;
                dstY1 = vpY + vpH;
            } else {
                glfbo.glBindFramebufferEXT(GLFbo.GL_DRAW_FRAMEBUFFER_EXT, dst.getId());
                dstX1 = dst.getWidth();
                dstY1 = dst.getHeight();
            }

            int mask = 0;

            if(copyColor){
                mask|=GL.GL_COLOR_BUFFER_BIT;
            }

            if (copyDepth) {
                mask |= GL.GL_DEPTH_BUFFER_BIT;
            }

            glfbo.glBlitFramebufferEXT(srcX0, srcY0, srcX1, srcY1,
                    dstX0, dstY0, dstX1, dstY1, mask,
                    GL.GL_NEAREST);


            glfbo.glBindFramebufferEXT(GLFbo.GL_FRAMEBUFFER_EXT, prevFBO);
        } else {
            throw new RendererException("Framebuffer blitting not supported by the video hardware");
        }
    }

    private void checkFrameBufferError() {
        int status = glfbo.glCheckFramebufferStatusEXT(GLFbo.GL_FRAMEBUFFER_EXT);
        switch (status) {
            case GLFbo.GL_FRAMEBUFFER_COMPLETE_EXT:
                break;
            case GLFbo.GL_FRAMEBUFFER_UNSUPPORTED_EXT:
                //Choose different formats
                throw new IllegalStateException("Framebuffer object format is "
                        + "unsupported by the video hardware.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT_EXT:
                throw new IllegalStateException("Framebuffer has erroneous attachment.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT_EXT:
                throw new IllegalStateException("Framebuffer doesn't have any renderbuffers attached.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS_EXT:
                throw new IllegalStateException("Framebuffer attachments must have same dimensions.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_FORMATS_EXT:
                throw new IllegalStateException("Framebuffer attachments must have same formats.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER_EXT:
                throw new IllegalStateException("Incomplete draw buffer.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER_EXT:
                throw new IllegalStateException("Incomplete read buffer.");
            case GLFbo.GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE_EXT:
                throw new IllegalStateException("Incomplete multisample buffer.");
            default:
                //Programming error; will fail on all hardware
                throw new IllegalStateException("Some video driver error "
                        + "or programming error occurred. "
                        + "Framebuffer object status is invalid. ");
        }
    }

    private void updateRenderBuffer(FrameBuffer fb, RenderBuffer rb) {
        int id = rb.getId();
        if (id == -1) {
            glfbo.glGenRenderbuffersEXT(intBuf1);
            id = intBuf1.get(0);
            rb.setId(id);
        }

        if (context.boundRB != id) {
            glfbo.glBindRenderbufferEXT(GLFbo.GL_RENDERBUFFER_EXT, id);
            context.boundRB = id;
        }

        int rbSize = limits.get(Limits.RenderBufferSize);
        if (fb.getWidth() > rbSize || fb.getHeight() > rbSize) {
            throw new RendererException("Resolution " + fb.getWidth()
                    + ":" + fb.getHeight() + " is not supported.");
        }

        GLImageFormat glFmt = texUtil.getImageFormatWithError(rb.getFormat(), fb.isSrgb());

        if (fb.getSamples() > 1 && caps.contains(Caps.FrameBufferMultisample)) {
            int samples = fb.getSamples();
            int maxSamples = limits.get(Limits.FrameBufferSamples);
            if (maxSamples < samples) {
                samples = maxSamples;
            }
            glfbo.glRenderbufferStorageMultisampleEXT(GLFbo.GL_RENDERBUFFER_EXT,
                    samples,
                    glFmt.internalFormat,
                    fb.getWidth(),
                    fb.getHeight());
        } else {
            glfbo.glRenderbufferStorageEXT(GLFbo.GL_RENDERBUFFER_EXT,
                    glFmt.internalFormat,
                    fb.getWidth(),
                    fb.getHeight());
        }
    }

    private int convertAttachmentSlot(int attachmentSlot) {
        // can also add support for stencil here
        if (attachmentSlot == FrameBuffer.SLOT_DEPTH) {
            return GLFbo.GL_DEPTH_ATTACHMENT_EXT;
        } else if (attachmentSlot == FrameBuffer.SLOT_DEPTH_STENCIL) {
            // NOTE: Using depth stencil format requires GL3, this is already
            // checked via render caps.
            return GL3.GL_DEPTH_STENCIL_ATTACHMENT;
        } else if (attachmentSlot < 0 || attachmentSlot >= 16) {
            throw new UnsupportedOperationException("Invalid FBO attachment slot: " + attachmentSlot);
        }

        return GLFbo.GL_COLOR_ATTACHMENT0_EXT + attachmentSlot;
    }

    public void updateRenderTexture(FrameBuffer fb, RenderBuffer rb) {
        Texture tex = rb.getTexture();
        Image image = tex.getImage();
        if (image.isUpdateNeeded()) {
            // Check NPOT requirements
            checkNonPowerOfTwo(tex);

            updateTexImageData(image, tex.getType(), 0, false);

            // NOTE: For depth textures, sets nearest/no-mips mode
            // Required to fix "framebuffer unsupported"
            // for old NVIDIA drivers!
            setupTextureParams(0, tex);
        }

        if (rb.getLayer() < 0){
            glfbo.glFramebufferTexture2DEXT(GLFbo.GL_FRAMEBUFFER_EXT,
                    convertAttachmentSlot(rb.getSlot()),
                    convertTextureType(tex.getType(), image.getMultiSamples(), rb.getFace()),
                    image.getId(),
                    rb.getLevel());
        } else {
            glfbo.glFramebufferTextureLayerEXT(GLFbo.GL_FRAMEBUFFER_EXT,
                    convertAttachmentSlot(rb.getSlot()),
                    image.getId(),
                    rb.getLevel(),
                    rb.getLayer());
        }
    }

    public void updateFrameBufferAttachment(FrameBuffer fb, RenderBuffer rb) {
        boolean needAttach;
        if (rb.getTexture() == null) {
            // if it hasn't been created yet, then attach is required.
            needAttach = rb.getId() == -1;
            updateRenderBuffer(fb, rb);
        } else {
            needAttach = false;
            updateRenderTexture(fb, rb);
        }
        if (needAttach) {
            glfbo.glFramebufferRenderbufferEXT(GLFbo.GL_FRAMEBUFFER_EXT,
                    convertAttachmentSlot(rb.getSlot()),
                    GLFbo.GL_RENDERBUFFER_EXT,
                    rb.getId());
        }
    }

    private void bindFrameBuffer(FrameBuffer fb) {
        if (fb == null) {
            if (context.boundFBO != defaultFBO) {
                glfbo.glBindFramebufferEXT(GLFbo.GL_FRAMEBUFFER_EXT, defaultFBO);
                statistics.onFrameBufferUse(null, true);
                context.boundFBO = defaultFBO;
                context.boundFB = null;
            }
        } else {
            assert fb.getId() != -1 && fb.getId() != 0;
            if (context.boundFBO != fb.getId()) {
                glfbo.glBindFramebufferEXT(GLFbo.GL_FRAMEBUFFER_EXT, fb.getId());
                context.boundFBO = fb.getId();
                context.boundFB = fb;
                statistics.onFrameBufferUse(fb, true);
            } else {
                statistics.onFrameBufferUse(fb, false);
            }
        }
    }

    public void updateFrameBuffer(FrameBuffer fb) {
        if (fb.getNumColorBuffers() == 0 && fb.getDepthBuffer() == null) {
            throw new IllegalArgumentException("The framebuffer: " + fb
                    + "\nDoesn't have any color/depth buffers");
        }

        int id = fb.getId();
        if (id == -1) {
            glfbo.glGenFramebuffersEXT(intBuf1);
            id = intBuf1.get(0);
            fb.setId(id);
            objManager.registerObject(fb);
            statistics.onNewFrameBuffer();
        }

        bindFrameBuffer(fb);

        FrameBuffer.RenderBuffer depthBuf = fb.getDepthBuffer();
        if (depthBuf != null) {
            updateFrameBufferAttachment(fb, depthBuf);
        }

        for (int i = 0; i < fb.getNumColorBuffers(); i++) {
            FrameBuffer.RenderBuffer colorBuf = fb.getColorBuffer(i);
            updateFrameBufferAttachment(fb, colorBuf);
        }

        setReadDrawBuffers(fb);
        checkFrameBufferError();

        fb.clearUpdateNeeded();
    }

    public Vector2f[] getFrameBufferSamplePositions(FrameBuffer fb) {
        if (fb.getSamples() <= 1) {
            throw new IllegalArgumentException("Framebuffer must be multisampled");
        }
        if (!caps.contains(Caps.TextureMultisample)) {
            throw new RendererException("Multisampled textures are not supported");
        }

        setFrameBuffer(fb);

        Vector2f[] samplePositions = new Vector2f[fb.getSamples()];
        FloatBuffer samplePos = BufferUtils.createFloatBuffer(2);
        for (int i = 0; i < samplePositions.length; i++) {
            glext.glGetMultisample(GLExt.GL_SAMPLE_POSITION, i, samplePos);
            samplePos.clear();
            samplePositions[i] = new Vector2f(samplePos.get(0) - 0.5f,
                    samplePos.get(1) - 0.5f);
        }
        return samplePositions;
    }

    @Override
    public void setMainFrameBufferOverride(FrameBuffer fb) {
        mainFbOverride = null;
        if (context.boundFBO == 0) {
            // Main FB is now set to fb, make sure its bound
            setFrameBuffer(fb);
        }
        mainFbOverride = fb;
    }


    @Override
    public FrameBuffer getCurrentFrameBuffer() {
        if(mainFbOverride!=null){
            return mainFbOverride;
        }
        return context.boundFB;
    }

    public void setReadDrawBuffers(FrameBuffer fb) {
        if (gl2 == null) {
            return;
        }

        final int NONE    = -2;
        final int INITIAL = -1;
        final int MRT_OFF = 100;

        if (fb != null) {
          
            if (fb.getNumColorBuffers() == 0) {
                // make sure to select NONE as draw buf
                // no color buffer attached.                
                gl2.glDrawBuffer(GL.GL_NONE);             
                gl2.glReadBuffer(GL.GL_NONE);                 
            } else {
                if (fb.getNumColorBuffers() > limits.get(Limits.FrameBufferAttachments)) {
                    throw new RendererException("Framebuffer has more color "
                            + "attachments than are supported"
                            + " by the video hardware!");
                }
                if (fb.isMultiTarget()) {
                    if (!caps.contains(Caps.FrameBufferMRT)) {
                        throw new RendererException("Multiple render targets "
                                + " are not supported by the video hardware");
                    }
                    if (fb.getNumColorBuffers() > limits.get(Limits.FrameBufferMrtAttachments)) {
                        throw new RendererException("Framebuffer has more"
                                + " multi targets than are supported"
                                + " by the video hardware!");
                    }

                    intBuf16.clear();
                    for (int i = 0; i < fb.getNumColorBuffers(); i++) {
                        intBuf16.put(GLFbo.GL_COLOR_ATTACHMENT0_EXT + i);
                    }

                    intBuf16.flip();
                    glext.glDrawBuffers(intBuf16);
                } else {
                    RenderBuffer rb = fb.getColorBuffer(fb.getTargetIndex());
                    // select this draw buffer
                    gl2.glDrawBuffer(GLFbo.GL_COLOR_ATTACHMENT0_EXT + rb.getSlot());
                    // select this read buffer
                    gl2.glReadBuffer(GLFbo.GL_COLOR_ATTACHMENT0_EXT + rb.getSlot());
                }
            }
        }

    }

    @Override
    public void setFrameBuffer(FrameBuffer fb) {
        if (fb == null && mainFbOverride != null) {
            fb = mainFbOverride;
        }

        if (context.boundFB == fb) {
            if (fb == null || !fb.isUpdateNeeded()) {
                return;
            }
        }

        if (!caps.contains(Caps.FrameBuffer)) {
            throw new RendererException("Framebuffer objects are not supported"
                    + " by the video hardware");
        }

        // generate mipmaps for last FB if needed
        if (context.boundFB != null && (context.boundFB.getMipMapsGenerationHint()!=null?context.boundFB.getMipMapsGenerationHint():generateMipmapsForFramebuffers)) {
            for (int i = 0; i < context.boundFB.getNumColorBuffers(); i++) {
                RenderBuffer rb = context.boundFB.getColorBuffer(i);
                Texture tex = rb.getTexture();
                if (tex != null && tex.getMinFilter().usesMipMapLevels()) {
                    try {
                        final int textureUnitIndex = 0;
                        setTexture(textureUnitIndex, rb.getTexture());
                    } catch (TextureUnitException exception) {
                        throw new RuntimeException("Renderer lacks texture units?");
                    }
                    if (tex.getType() == Texture.Type.CubeMap) {
                        glfbo.glGenerateMipmapEXT(GL.GL_TEXTURE_CUBE_MAP);
                    } else {
                        int textureType = convertTextureType(tex.getType(), tex.getImage().getMultiSamples(), rb.getFace());
                        glfbo.glGenerateMipmapEXT(textureType);
                    }
                }
            }
        }

        if (fb == null) {
            bindFrameBuffer(null);
        } else {
            if (fb.isUpdateNeeded()) {
                updateFrameBuffer(fb);
            } else {
                bindFrameBuffer(fb);
            }

            // update viewport to reflect framebuffer's resolution
            setViewPort(0, 0, fb.getWidth(), fb.getHeight());

            assert fb.getId() > 0;
            assert context.boundFBO == fb.getId();

            context.boundFB = fb;
            if (debug && caps.contains(Caps.GLDebug)) {
                if (fb.getName() != null) glext.glObjectLabel(GL3.GL_FRAMEBUFFER, fb.getId(), fb.getName());
            }
        }
    }

    @Override
    public void readFrameBuffer(FrameBuffer fb, ByteBuffer byteBuf) {
        readFrameBufferWithGLFormat(fb, byteBuf, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE);
    }

    private void readFrameBufferWithGLFormat(FrameBuffer fb, ByteBuffer byteBuf, int glFormat, int dataType) {
        if (fb != null) {
            RenderBuffer rb = fb.getColorBuffer();
            if (rb == null) {
                throw new IllegalArgumentException("Specified framebuffer"
                        + " does not have a colorbuffer");
            }

            setFrameBuffer(fb);
         

        } else {
            setFrameBuffer(null);
        }

        gl.glReadPixels(vpX, vpY, vpW, vpH, glFormat, dataType, byteBuf);
    }

    @Override
    public void readFrameBufferWithFormat(FrameBuffer fb, ByteBuffer byteBuf, Image.Format format) {
        GLImageFormat glFormat = texUtil.getImageFormatWithError(format, false);
        readFrameBufferWithGLFormat(fb, byteBuf, glFormat.format, glFormat.dataType);
    }

    private void deleteRenderBuffer(FrameBuffer fb, RenderBuffer rb) {
        intBuf1.put(0, rb.getId());
        glfbo.glDeleteRenderbuffersEXT(intBuf1);
    }

    @Override
    public void deleteFrameBuffer(FrameBuffer fb) {
        if (fb.getId() != -1) {
            if (context.boundFBO == fb.getId()) {
                glfbo.glBindFramebufferEXT(GLFbo.GL_FRAMEBUFFER_EXT, 0);
                context.boundFBO = 0;
            }

            if (fb.getDepthBuffer() != null) {
                deleteRenderBuffer(fb, fb.getDepthBuffer());
            }
            if (fb.getColorBuffer() != null) {
                deleteRenderBuffer(fb, fb.getColorBuffer());
            }

            intBuf1.put(0, fb.getId());
            glfbo.glDeleteFramebuffersEXT(intBuf1);
            fb.resetObject();

            statistics.onDeleteFrameBuffer();
        }
    }

    /*********************************************************************\
     |* Textures                                                          *|
     \*********************************************************************/
    private int convertTextureType(Texture.Type type, int samples, int face) {
        if (samples > 1 && !caps.contains(Caps.TextureMultisample)) {
            throw new RendererException("Multisample textures are not supported" +
                    " by the video hardware.");
        }

        switch (type) {
            case TwoDimensional:
                if (samples > 1) {
                    return GLExt.GL_TEXTURE_2D_MULTISAMPLE;
                } else {
                    return GL.GL_TEXTURE_2D;
                }
            case TwoDimensionalArray:
                if (!caps.contains(Caps.TextureArray)) {
                    throw new RendererException("Array textures are not supported"
                            + " by the video hardware.");
                }
                if (samples > 1) {
                    return GLExt.GL_TEXTURE_2D_MULTISAMPLE_ARRAY;
                } else {
                    return GLExt.GL_TEXTURE_2D_ARRAY_EXT;
                }
            case ThreeDimensional:
                if (!caps.contains(Caps.OpenGL20) && !caps.contains(Caps.OpenGLES30)) {
                    throw new RendererException("3D textures are not supported" +
                            " by the video hardware.");
                }
                return GL2.GL_TEXTURE_3D;
            case CubeMap:
                if (face < 0) {
                    return GL.GL_TEXTURE_CUBE_MAP;
                } else if (face < 6) {
                    return GL.GL_TEXTURE_CUBE_MAP_POSITIVE_X + face;
                } else {
                    throw new UnsupportedOperationException("Invalid cube map face index: " + face);
                }
            default:
                throw new UnsupportedOperationException("Unknown texture type: " + type);
        }
    }

    private int convertMagFilter(Texture.MagFilter filter) {
        switch (filter) {
            case Bilinear:
                return GL.GL_LINEAR;
            case Nearest:
                return GL.GL_NEAREST;
            default:
                throw new UnsupportedOperationException("Unknown mag filter: " + filter);
        }
    }

    private int convertMinFilter(Texture.MinFilter filter, boolean haveMips) {
        if (haveMips){
            switch (filter) {
                case Trilinear:
                    return GL.GL_LINEAR_MIPMAP_LINEAR;
                case BilinearNearestMipMap:
                    return GL.GL_LINEAR_MIPMAP_NEAREST;
                case NearestLinearMipMap:
                    return GL.GL_NEAREST_MIPMAP_LINEAR;
                case NearestNearestMipMap:
                    return GL.GL_NEAREST_MIPMAP_NEAREST;
                case BilinearNoMipMaps:
                    return GL.GL_LINEAR;
                case NearestNoMipMaps:
                    return GL.GL_NEAREST;
                default:
                    throw new UnsupportedOperationException("Unknown min filter: " + filter);
            }
        } else {
            switch (filter) {
                case Trilinear:
                case BilinearNearestMipMap:
                case BilinearNoMipMaps:
                    return GL.GL_LINEAR;
                case NearestLinearMipMap:
                case NearestNearestMipMap:
                case NearestNoMipMaps:
                    return GL.GL_NEAREST;
                default:
                    throw new UnsupportedOperationException("Unknown min filter: " + filter);
            }
        }
    }

    private int convertWrapMode(Texture.WrapMode mode) {
        switch (mode) {
            case BorderClamp:
            case Clamp:
                // fall through
            case EdgeClamp:
                return GL.GL_CLAMP_TO_EDGE;
            case Repeat:
                return GL.GL_REPEAT;
            case MirroredRepeat:
                return GL.GL_MIRRORED_REPEAT;
            default:
                throw new UnsupportedOperationException("Unknown wrap mode: " + mode);
        }
    }

    @SuppressWarnings("fallthrough")
    private void setupTextureParams(int unit, Texture tex) {
        Image image = tex.getImage();
        int samples = image != null ? image.getMultiSamples() : 1;
        int target = convertTextureType(tex.getType(), samples, -1);

        if (samples > 1) {
            bindTextureOnly(target, image, unit);
            return;
        }

        boolean haveMips = true;
        if (image != null) {
            haveMips = image.isGeneratedMipmapsRequired() || image.hasMipmaps();
        }

        LastTextureState curState = image.getLastTextureState();

        if (curState.magFilter != tex.getMagFilter()) {
            bindTextureAndUnit(target, image, unit);
            gl.glTexParameteri(target, GL.GL_TEXTURE_MAG_FILTER, convertMagFilter(tex.getMagFilter()));
            curState.magFilter = tex.getMagFilter();
        }
        if (curState.minFilter != tex.getMinFilter()) {
            bindTextureAndUnit(target, image, unit);
            gl.glTexParameteri(target, GL.GL_TEXTURE_MIN_FILTER, convertMinFilter(tex.getMinFilter(), haveMips));
            curState.minFilter = tex.getMinFilter();
        }

        int desiredAnisoFilter = tex.getAnisotropicFilter() == 0
                ? defaultAnisotropicFilter
                : tex.getAnisotropicFilter();

        if (caps.contains(Caps.TextureFilterAnisotropic)
                && curState.anisoFilter != desiredAnisoFilter) {
            bindTextureAndUnit(target, image, unit);
            gl.glTexParameterf(target,
                    GLExt.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                    desiredAnisoFilter);
            curState.anisoFilter = desiredAnisoFilter;
        }

        switch (tex.getType()) {
            case ThreeDimensional:
            case CubeMap: // cubemaps use 3D coords
                if (gl2 != null && (caps.contains(Caps.OpenGL20) || caps.contains(Caps.OpenGLES30)) && curState.rWrap != tex.getWrap(WrapAxis.R)) {
                    bindTextureAndUnit(target, image, unit);
                    gl.glTexParameteri(target, GL2.GL_TEXTURE_WRAP_R, convertWrapMode(tex.getWrap(WrapAxis.R)));
                    curState.rWrap = tex.getWrap(WrapAxis.R);
                }
                //There is no break statement on purpose here
            case TwoDimensional:
            case TwoDimensionalArray:
                if (curState.tWrap != tex.getWrap(WrapAxis.T)) {
                    bindTextureAndUnit(target, image, unit);
                    gl.glTexParameteri(target, GL.GL_TEXTURE_WRAP_T, convertWrapMode(tex.getWrap(WrapAxis.T)));
                    image.getLastTextureState().tWrap = tex.getWrap(WrapAxis.T);
                }
                if (curState.sWrap != tex.getWrap(WrapAxis.S)) {
                    bindTextureAndUnit(target, image, unit);
                    gl.glTexParameteri(target, GL.GL_TEXTURE_WRAP_S, convertWrapMode(tex.getWrap(WrapAxis.S)));
                    curState.sWrap = tex.getWrap(WrapAxis.S);
                }
                break;
            default:
                throw new UnsupportedOperationException("Unknown texture type: " + tex.getType());
        }

        ShadowCompareMode texCompareMode = tex.getShadowCompareMode();
        if ( (gl2 != null || caps.contains(Caps.OpenGLES30)) && curState.shadowCompareMode != texCompareMode) {
            bindTextureAndUnit(target, image, unit);
            if (texCompareMode != ShadowCompareMode.Off) {
                gl.glTexParameteri(target, GL2.GL_TEXTURE_COMPARE_MODE, GL2.GL_COMPARE_REF_TO_TEXTURE);
                if (texCompareMode == ShadowCompareMode.GreaterOrEqual) {
                    gl.glTexParameteri(target, GL2.GL_TEXTURE_COMPARE_FUNC, GL.GL_GEQUAL);
                } else {
                    gl.glTexParameteri(target, GL2.GL_TEXTURE_COMPARE_FUNC, GL.GL_LEQUAL);
                }
            } else {
                gl.glTexParameteri(target, GL2.GL_TEXTURE_COMPARE_MODE, GL.GL_NONE);
            }
            curState.shadowCompareMode = texCompareMode;
        }

        // If at this point we didn't bind the texture, bind it now
        bindTextureOnly(target, image, unit);
    }

    /**
     * Validates if a potentially NPOT texture is supported by the hardware.
     * <p>
     * Textures with power-of-2 dimensions are supported on all hardware, however
     * non-power-of-2 textures may or may not be supported depending on which
     * texturing features are used.
     *
     * @param tex The texture to validate.
     * @throws RendererException If the texture is not supported by the hardware
     */
    private void checkNonPowerOfTwo(Texture tex) {
        if (!tex.getImage().isNPOT()) {
            // Texture is power-of-2, safe to use.
            return;
        }

        if (caps.contains(Caps.NonPowerOfTwoTextures)) {
            // Texture is NPOT, but it is supported by video hardware.
            return;
        }

        // Maybe we have some / partial support for NPOT?
        if (!caps.contains(Caps.PartialNonPowerOfTwoTextures)) {
            // Cannot use any type of NPOT texture (uncommon)
            throw new RendererException("non-power-of-2 textures are not "
                    + "supported by the video hardware");
        }

        // Partial NPOT supported..
        if (tex.getMinFilter().usesMipMapLevels()) {
            throw new RendererException("non-power-of-2 textures with mip-maps "
                    + "are not supported by the video hardware");
        }

        switch (tex.getType()) {
            case CubeMap:
            case ThreeDimensional:
                if (tex.getWrap(WrapAxis.R) != Texture.WrapMode.EdgeClamp) {
                    throw new RendererException("repeating non-power-of-2 textures "
                            + "are not supported by the video hardware");
                }
                // fallthrough intentional!!!
            case TwoDimensionalArray:
            case TwoDimensional:
                if (tex.getWrap(WrapAxis.S) != Texture.WrapMode.EdgeClamp
                        || tex.getWrap(WrapAxis.T) != Texture.WrapMode.EdgeClamp) {
                    throw new RendererException("repeating non-power-of-2 textures "
                            + "are not supported by the video hardware");
                }
                break;
            default:
                throw new UnsupportedOperationException("unrecognized texture type");
        }
    }

    /**
     * Ensures that the texture is bound to the given unit
     * and that the unit is currently active (for modification).
     *
     * @param target The texture target, one of GL_TEXTURE_***
     * @param img The image texture to bind
     * @param unit At what unit to bind the texture.
     */
    private void bindTextureAndUnit(int target, Image img, int unit) {
        if (context.boundTextureUnit != unit) {
            gl.glActiveTexture(GL.GL_TEXTURE0 + unit);
            context.boundTextureUnit = unit;
        }
        if (context.boundTextures[unit]==null||context.boundTextures[unit].get() != img.getWeakRef().get()) {
            gl.glBindTexture(target, img.getId());
            context.boundTextures[unit] = img.getWeakRef();
            statistics.onTextureUse(img, true);
        } else {
            statistics.onTextureUse(img, false);
        }
    }

    /**
     * Ensures that the texture is bound to the given unit,
     * but does not care if the unit is active (for rendering).
     *
     * @param target The texture target, one of GL_TEXTURE_***
     * @param img The image texture to bind
     * @param unit At what unit to bind the texture.
     */
    private void bindTextureOnly(int target, Image img, int unit) {
        if (context.boundTextures[unit] == null || context.boundTextures[unit].get() != img.getWeakRef().get()) {
            if (context.boundTextureUnit != unit) {
                gl.glActiveTexture(GL.GL_TEXTURE0 + unit);
                context.boundTextureUnit = unit;
            }
            gl.glBindTexture(target, img.getId());
            context.boundTextures[unit] = img.getWeakRef();
            statistics.onTextureUse(img, true);
        } else {
            statistics.onTextureUse(img, false);
        }
    }

    /**
     * Uploads the given image to the GL driver.
     *
     * @param img The image to upload
     * @param type How the data in the image argument should be interpreted.
     * @param unit The texture slot to be used to upload the image, not important
     * @param scaleToPot If true, the image will be scaled to power-of-2 dimensions
     * before being uploaded.
     */
    public void updateTexImageData(Image img, Texture.Type type, int unit, boolean scaleToPot) {
        int texId = img.getId();
        if (texId == -1) {
            // create texture
            gl.glGenTextures(intBuf1);
            texId = intBuf1.get(0);
            img.setId(texId);
            objManager.registerObject(img);

            statistics.onNewTexture();
        }

        // bind texture
        int target = convertTextureType(type, img.getMultiSamples(), -1);
        bindTextureAndUnit(target, img, unit);

        int imageSamples = img.getMultiSamples();
        if (imageSamples <= 1) {
            if (!img.hasMipmaps() && img.isGeneratedMipmapsRequired()) {
                // Image does not have mipmaps, but they are required.
                // Generate from base level.

                if (!caps.contains(Caps.FrameBuffer) && gl2 != null) {
                    gl2.glTexParameteri(target, GL2.GL_GENERATE_MIPMAP, GL.GL_TRUE);
                    img.setMipmapsGenerated(true);
                } else {
                    // For OpenGL3 and up.
                    // We'll generate mipmaps via glGenerateMipmapEXT (see below)
                }
            } else if (caps.contains(Caps.OpenGL20) || caps.contains(Caps.OpenGLES30)) {
                if (img.hasMipmaps()) {
                    // Image already has mipmaps, set the max level based on the
                    // number of mipmaps we have.
                    gl.glTexParameteri(target, GL2.GL_TEXTURE_MAX_LEVEL, img.getMipMapSizes().length - 1);
                } else {
                    // Image does not have mipmaps, and they are not required.
                    // Specify that the texture has no mipmaps.
                    gl.glTexParameteri(target, GL2.GL_TEXTURE_MAX_LEVEL, 0);
                }
            }
        } else {
            // Check if graphics card doesn't support multisample textures
            if (!caps.contains(Caps.TextureMultisample)) {
                throw new RendererException("Multisample textures are not supported by the video hardware");
            }

            if (img.isGeneratedMipmapsRequired() || img.hasMipmaps()) {
                throw new RendererException("Multisample textures do not support mipmaps");
            }

            if (img.getFormat().isDepthFormat()) {
                img.setMultiSamples(Math.min(limits.get(Limits.DepthTextureSamples), imageSamples));
            } else {
                img.setMultiSamples(Math.min(limits.get(Limits.ColorTextureSamples), imageSamples));
            }

            scaleToPot = false;
        }

        // Check if graphics card doesn't support depth textures
        if (img.getFormat().isDepthFormat() && !caps.contains(Caps.DepthTexture)) {
            throw new RendererException("Depth textures are not supported by the video hardware");
        }

        if (target == GL.GL_TEXTURE_CUBE_MAP) {
            // Check max texture size before upload
            int cubeSize = limits.get(Limits.CubemapSize);
            if (img.getWidth() > cubeSize || img.getHeight() > cubeSize) {
                throw new RendererException("Cannot upload cubemap " + img + ". The maximum supported cubemap resolution is " + cubeSize);
            }
            if (img.getWidth() != img.getHeight()) {
                throw new RendererException("Cubemaps must have square dimensions");
            }
        } else {
            int texSize = limits.get(Limits.TextureSize);
            if (img.getWidth() > texSize || img.getHeight() > texSize) {
                throw new RendererException("Cannot upload texture " + img + ". The maximum supported texture resolution is " + texSize);
            }
        }

        Image imageForUpload;
        if (scaleToPot) {
            imageForUpload = MipMapGenerator.resizeToPowerOf2(img);
        } else {
            imageForUpload = img;
        }
        if (target == GL.GL_TEXTURE_CUBE_MAP) {
            List<ByteBuffer> data = imageForUpload.getData();
            if (data.size() != 6) {
                logger.log(Level.WARNING, "Invalid texture: {0}\n"
                        + "Cubemap textures must contain 6 data units.", img);
                return;
            }
            for (int i = 0; i < 6; i++) {
                texUtil.uploadTexture(imageForUpload, GL.GL_TEXTURE_CUBE_MAP_POSITIVE_X + i, i, linearizeSrgbImages);
            }
        } else if (target == GLExt.GL_TEXTURE_2D_ARRAY_EXT) {
            if (!caps.contains(Caps.TextureArray)) {
                throw new RendererException("Texture arrays not supported by graphics hardware");
            }

            List<ByteBuffer> data = imageForUpload.getData();

            // -1 index specifies prepare data for 2D Array
            texUtil.uploadTexture(imageForUpload, target, -1, linearizeSrgbImages);

            for (int i = 0; i < data.size(); i++) {
                // upload each slice of 2D array in turn
                // this time with the appropriate index
                texUtil.uploadTexture(imageForUpload, target, i, linearizeSrgbImages);
            }
        } else {
            texUtil.uploadTexture(imageForUpload, target, 0, linearizeSrgbImages);
        }

        if (img.getMultiSamples() != imageSamples) {
            img.setMultiSamples(imageSamples);
        }

        if (caps.contains(Caps.FrameBuffer) || gl2 == null) {
            if (!img.hasMipmaps() && img.isGeneratedMipmapsRequired() && img.getData(0) != null) {
                glfbo.glGenerateMipmapEXT(target);
                img.setMipmapsGenerated(true);
            }
        }

        img.clearUpdateNeeded();
    }

    @Override
    public void setTexture(int unit, Texture tex) throws TextureUnitException {
        if (unit < 0 || unit >= RenderContext.maxTextureUnits) {
            throw new TextureUnitException();
        }
        
        Image image = tex.getImage();
        if (image.isUpdateNeeded() || (image.isGeneratedMipmapsRequired() && !image.isMipmapsGenerated())) {
            // Check NPOT requirements
            boolean scaleToPot = false;

            try {
                checkNonPowerOfTwo(tex);
            } catch (RendererException ex) {
                if (logger.isLoggable(Level.WARNING)) {
                    int nextWidth = FastMath.nearestPowerOfTwo(tex.getImage().getWidth());
                    int nextHeight = FastMath.nearestPowerOfTwo(tex.getImage().getHeight());
                    logger.log(Level.WARNING,
                            "Non-power-of-2 textures are not supported! Scaling texture '" + tex.getName() +
                                    "' of size " + tex.getImage().getWidth() + "x" + tex.getImage().getHeight() +
                                    " to " + nextWidth + "x" + nextHeight);
                }
                scaleToPot = true;
            }

            updateTexImageData(image, tex.getType(), unit, scaleToPot);
        }

        int texId = image.getId();
        assert texId != -1;

        setupTextureParams(unit, tex);
        if (debug && caps.contains(Caps.GLDebug)) {
            if (tex.getName() != null) glext.glObjectLabel(GL.GL_TEXTURE, tex.getImage().getId(), tex.getName());
        }
    }
    
    @Override
    public void setTextureImage(int unit, TextureImage tex) throws TextureUnitException {
        if (unit < 0 || unit >= RenderContext.maxTextureUnits) {
            throw new TextureUnitException();
        }
        WeakReference<Image> ref = context.boundTextures[unit];
        boolean bindRequired = tex.clearUpdateNeeded() || ref == null || ref.get() != tex.getImage().getWeakRef().get();
        setTexture(unit, tex.getTexture());
        if (gl4 != null && bindRequired) {
            tex.bindImage(gl4, texUtil, unit);
        }
    }
    
    @Override
    public void setUniformBufferObject(int bindingPoint, BufferObject bufferObject) {
        if (bufferObject.isUpdateNeeded()) {
            updateUniformBufferObjectData(bufferObject);
        }

        if (context.boundBO[bindingPoint] == null || context.boundBO[bindingPoint].get() != bufferObject) {
            gl3.glBindBufferBase(GL3.GL_UNIFORM_BUFFER, bindingPoint, bufferObject.getId());
            bufferObject.setBinding(bindingPoint);
            context.boundBO[bindingPoint] = bufferObject.getWeakRef();
        }

        bufferObject.setBinding(bindingPoint);

        if (debug && caps.contains(Caps.GLDebug)) {
            if (bufferObject.getName() != null) glext.glObjectLabel(GLExt.GL_BUFFER, bufferObject.getId(), bufferObject.getName());
        }

    }

    @Override
    public void setShaderStorageBufferObject(int bindingPoint, BufferObject bufferObject) {
        if (bufferObject.isUpdateNeeded()) {
            updateShaderStorageBufferObjectData(bufferObject);
        }
        if (context.boundBO[bindingPoint] == null || context.boundBO[bindingPoint].get() != bufferObject) {
            gl4.glBindBufferBase(GL4.GL_SHADER_STORAGE_BUFFER, bindingPoint, bufferObject.getId());
            bufferObject.setBinding(bindingPoint);
            context.boundBO[bindingPoint] = bufferObject.getWeakRef();
        }
        bufferObject.setBinding(bindingPoint);

        if (debug && caps.contains(Caps.GLDebug)) {
            if (bufferObject.getName() != null) glext.glObjectLabel(GLExt.GL_BUFFER, bufferObject.getId(), bufferObject.getName());
        }
    }

    /**
     * @deprecated Use modifyTexture(Texture2D dest, Image src, int destX, int destY, int srcX, int srcY, int areaW, int areaH)
     */
    @Deprecated
    @Override
    public void modifyTexture(Texture tex, Image pixels, int x, int y) {
        final int textureUnitIndex = 0;
        try {
            setTexture(textureUnitIndex, tex);
        } catch (TextureUnitException exception) {
            throw new RuntimeException("Renderer lacks texture units?");
        }

        if(caps.contains(Caps.OpenGLES20) && pixels.getFormat()!=tex.getImage().getFormat()) {
            logger.log(Level.WARNING, "Incompatible texture subimage");
        }
        int target = convertTextureType(tex.getType(), pixels.getMultiSamples(), -1);
        texUtil.uploadSubTexture(target, pixels, 0, x, y,
                0, 0, pixels.getWidth(), pixels.getHeight(), linearizeSrgbImages);
    }

     /**
     * Copy a part of an image to a texture 2d.
     * @param dest The destination image, where the source will be copied
     * @param src The source image that contains the data to copy
     * @param destX First pixel of the destination image from where the src image will be drawn (x component)
     * @param destY First pixel of the destination image from where the src image will be drawn (y component)
     * @param srcX  First pixel to copy (x component)
     * @param srcY  First pixel to copy (y component)
     * @param areaW Width of the area to copy
     * @param areaH Height of the area to copy
     */
    public void modifyTexture(Texture2D dest, Image src, int destX, int destY,
            int srcX, int srcY, int areaW, int areaH) {
        final int textureUnitIndex = 0;
        try {
            setTexture(textureUnitIndex, dest);
        } catch (TextureUnitException exception) {
            throw new RuntimeException("Renderer lacks texture units?");
        }

        if(caps.contains(Caps.OpenGLES20) && src.getFormat()!=dest.getImage().getFormat()) {
            logger.log(Level.WARNING, "Incompatible texture subimage");
        }
        int target = convertTextureType(dest.getType(), src.getMultiSamples(), -1);
        texUtil.uploadSubTexture(target, src, 0, destX, destY,
                srcX, srcY, areaW, areaH, linearizeSrgbImages);
    }

    @Override
    public void deleteImage(Image image) {
        int texId = image.getId();
        if (texId != -1) {
            intBuf1.put(0, texId);
            intBuf1.position(0).limit(1);
            gl.glDeleteTextures(intBuf1);
            image.resetObject();

            statistics.onDeleteTexture();
        }
    }

    /*********************************************************************\
     |* Vertex Buffers and Attributes                                     *|
     \*********************************************************************/
    private int convertUsage(Usage usage) {
        switch (usage) {
            case Static:
                return GL.GL_STATIC_DRAW;
            case Dynamic:
                return GL.GL_DYNAMIC_DRAW;
            case Stream:
                return GL.GL_STREAM_DRAW;
            default:
                throw new UnsupportedOperationException("Unknown usage type.");
        }
    }

    private int convertFormat(Format format) {
        switch (format) {
            case Byte:
                return GL.GL_BYTE;
            case UnsignedByte:
                return GL.GL_UNSIGNED_BYTE;
            case Short:
                return GL.GL_SHORT;
            case UnsignedShort:
                return GL.GL_UNSIGNED_SHORT;
            case Int:
                return GL.GL_INT;
            case UnsignedInt:
                return GL.GL_UNSIGNED_INT;
            case Float:
                return GL.GL_FLOAT;
            case Double:
                return GL.GL_DOUBLE;
            default:
                throw new UnsupportedOperationException("Unknown buffer format.");

        }
    }

    @Override
    public void updateBufferData(VertexBuffer vb) {
        int bufId = vb.getId();
        boolean created = false;
        if (bufId == -1) {
            // create buffer
            gl.glGenBuffers(intBuf1);
            bufId = intBuf1.get(0);
            vb.setId(bufId);
            objManager.registerObject(vb);

            //statistics.onNewVertexBuffer();

            created = true;
        }

        // bind buffer
        int target;
        if (vb.getBufferType() == VertexBuffer.Type.Index) {
            target = GL.GL_ELEMENT_ARRAY_BUFFER;
            if (context.boundElementArrayVBO != bufId) {
                gl.glBindBuffer(target, bufId);
                context.boundElementArrayVBO = bufId;
                //statistics.onVertexBufferUse(vb, true);
            } else {
                //statistics.onVertexBufferUse(vb, false);
            }
        } else {
            target = GL.GL_ARRAY_BUFFER;
            if (context.boundArrayVBO != bufId) {
                gl.glBindBuffer(target, bufId);
                context.boundArrayVBO = bufId;
                //statistics.onVertexBufferUse(vb, true);
            } else {
                //statistics.onVertexBufferUse(vb, false);
            }
        }

        int usage = convertUsage(vb.getUsage());
        vb.getData().rewind();

        switch (vb.getFormat()) {
            case Byte:
            case UnsignedByte:
                gl.glBufferData(target, (ByteBuffer) vb.getData(), usage);
                break;
            case Short:
            case UnsignedShort:
                gl.glBufferData(target, (ShortBuffer) vb.getData(), usage);
                break;
            case Int:
            case UnsignedInt:
                glext.glBufferData(target, (IntBuffer) vb.getData(), usage);
                break;
            case Float:
                gl.glBufferData(target, (FloatBuffer) vb.getData(), usage);
                break;
            default:
                throw new UnsupportedOperationException("Unknown buffer format.");
        }

        vb.clearUpdateNeeded();
    }

    private int resolveUsageHint(BufferObject.AccessHint ah, BufferObject.NatureHint nh) {
        switch (ah) {
            case Dynamic: {
                switch (nh) {
                    case Draw:
                        return GL.GL_DYNAMIC_DRAW;
                    case Read:
                        return GL4.GL_DYNAMIC_READ;
                    case Copy:
                        return GL.GL_DYNAMIC_COPY;
                }
            }
            case Stream: {
                switch (nh) {
                    case Draw:
                        return GL.GL_STREAM_DRAW;
                    case Read:
                        return GL.GL_STREAM_READ;
                    case Copy:
                        return GL.GL_STREAM_COPY;
                }
            }
            case Static: {
                switch (nh) {
                    case Draw:
                        return GL.GL_STATIC_DRAW;
                    case Read:
                        return GL.GL_STATIC_READ;
                    case Copy:
                        return GL.GL_STATIC_COPY;
                }
            }
            default:
        }
        return -1;
    }

    @Override
    public void updateShaderStorageBufferObjectData(BufferObject bo) {
        if (!caps.contains(Caps.ShaderStorageBufferObject)) throw new IllegalArgumentException("The current video hardware doesn't support shader storage buffer objects ");
        updateBufferData(GL4.GL_SHADER_STORAGE_BUFFER, bo);
    }

    @Override
    public void updateUniformBufferObjectData(BufferObject bo) {
        if (!caps.contains(Caps.UniformBufferObject)) throw new IllegalArgumentException("The current video hardware doesn't support uniform buffer objects");
        updateBufferData(GL4.GL_UNIFORM_BUFFER, bo);
    }

    private void updateBufferData(int type, BufferObject bo) {
        int bufferId = bo.getId();
        int usage = resolveUsageHint(bo.getAccessHint(), bo.getNatureHint());
        if (usage == -1) {
            deleteBuffer(bo);
            return; // CpuOnly
        }

        if (bufferId == -1) {

            // create buffer
            intBuf1.clear();
            gl.glGenBuffers(intBuf1);
            bufferId = intBuf1.get(0);

            bo.setId(bufferId);

            objManager.registerObject(bo);
        }

        DirtyRegionsIterator it = bo.getDirtyRegions();
        BufferRegion reg;

        while ((reg = it.next()) != null) {
            gl3.glBindBuffer(type, bufferId);
            if (reg.isFullBufferRegion()) {
                ByteBuffer bbf = bo.getData();
                if (logger.isLoggable(java.util.logging.Level.FINER)) {
                    logger.log(java.util.logging.Level.FINER, "Update full buffer {0} with {1} bytes", new Object[] { bo, bbf.remaining() });
                }
                gl.glBufferData(type, bbf, usage);
                gl3.glBindBuffer(type, 0);
                reg.clearDirty();
                break;
            } else {
                if (logger.isLoggable(java.util.logging.Level.FINER)) {
                    logger.log(java.util.logging.Level.FINER, "Update region {0} of {1}", new Object[] { reg, bo });
                }
                gl.glBufferSubData(type, reg.getStart(), reg.getData());
                gl3.glBindBuffer(type, 0);
                reg.clearDirty();
            }
        }
        bo.clearUpdateNeeded();
    }

    @Override
    public void deleteBuffer(VertexBuffer vb) {
        int bufId = vb.getId();
        if (bufId != -1) {
            // delete buffer
            intBuf1.put(0, bufId);
            intBuf1.position(0).limit(1);
            gl.glDeleteBuffers(intBuf1);
            vb.resetObject();

            //statistics.onDeleteVertexBuffer();
        }
    }

    @Override
    public void deleteBuffer(final BufferObject bo) {

        int bufferId = bo.getId();
        if (bufferId == -1) {
            return;
        }

        intBuf1.clear();
        intBuf1.put(bufferId);
        intBuf1.flip();

        gl.glDeleteBuffers(intBuf1);

        bo.resetObject();
    }

    public void clearVertexAttribs() {
        IDList attribList = context.attribIndexList;
        for (int i = 0; i < attribList.oldLen; i++) {
            int idx = attribList.oldList[i];
            gl.glDisableVertexAttribArray(idx);
            WeakReference<VertexBuffer> ref = context.boundAttribs[idx];
            if (ref != null) {
                VertexBuffer buffer = ref.get();
                if (buffer != null && buffer.isInstanced()) {
                    glext.glVertexAttribDivisorARB(idx, 0);
                }
                context.boundAttribs[idx] = null;
            }
        }
        attribList.copyNewToOld();
    }

    public void setVertexAttrib(VertexBuffer vb, VertexBuffer idb) {
        if (vb.getBufferType() == VertexBuffer.Type.Index) {
            throw new IllegalArgumentException("Index buffers not allowed to be set to vertex attrib");
        }

        if (context.boundShaderProgram <= 0) {
            throw new IllegalStateException("Cannot render mesh without shader bound");
        }

        Attribute attrib = context.boundShader.getAttribute(vb.getBufferType());
        int loc = attrib.getLocation();
        if (loc == -1) {
            return; // not defined
        }
        if (loc == -2) {
            loc = gl.glGetAttribLocation(context.boundShaderProgram, "in" + vb.getBufferType().name());

            // not really the name of it in the shader (inPosition) but
            // the internal name of the enum (Position).
            if (loc < 0) {
                attrib.setLocation(-1);
                return; // not available in shader.
            } else {
                attrib.setLocation(loc);
            }
        }

        if (vb.isInstanced()) {
            if (!caps.contains(Caps.MeshInstancing)) {
                throw new RendererException("Instancing is required, "
                        + "but not supported by the "
                        + "graphics hardware");
            }
        }
        int slotsRequired = 1;
        if (vb.getNumComponents() > 4) {
            if (vb.getNumComponents() % 4 != 0) {
                throw new RendererException("Number of components in multi-slot "
                        + "buffers must be divisible by 4");
            }
            slotsRequired = vb.getNumComponents() / 4;
        }

        if (vb.isUpdateNeeded() && idb == null) {
            updateBufferData(vb);
        }

        WeakReference<VertexBuffer>[] attribs = context.boundAttribs;
        for (int i = 0; i < slotsRequired; i++) {
            if (!context.attribIndexList.moveToNew(loc + i)) {
                gl.glEnableVertexAttribArray(loc + i);
            }
        }
        if (attribs[loc]==null||attribs[loc].get() != vb) {
            // NOTE: Use id from interleaved buffer if specified
            int bufId = idb != null ? idb.getId() : vb.getId();
            assert bufId != -1;
            if (context.boundArrayVBO != bufId) {
                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, bufId);
                context.boundArrayVBO = bufId;
                //statistics.onVertexBufferUse(vb, true);
            } else {
                //statistics.onVertexBufferUse(vb, false);
            }

            if (slotsRequired == 1) {
                gl.glVertexAttribPointer(loc,
                        vb.getNumComponents(),
                        convertFormat(vb.getFormat()),
                        vb.isNormalized(),
                        vb.getStride(),
                        vb.getOffset());
            } else {
                for (int i = 0; i < slotsRequired; i++) {
                    // The pointer maps the next 4 floats in the slot.
                    // E.g.
                    // P1: XXXX____________XXXX____________
                    // P2: ____XXXX____________XXXX________
                    // P3: ________XXXX____________XXXX____
                    // P4: ____________XXXX____________XXXX
                    // stride = 4 bytes in float * 4 floats in slot * num slots
                    // offset = 4 bytes in float * 4 floats in slot * slot index
                    gl.glVertexAttribPointer(loc + i,
                            4,
                            convertFormat(vb.getFormat()),
                            vb.isNormalized(),
                            4 * 4 * slotsRequired,
                            4 * 4 * i);
                }
            }

            for (int i = 0; i < slotsRequired; i++) {
                int slot = loc + i;
                if (vb.isInstanced() && (attribs[slot] == null || attribs[slot].get() == null || !attribs[slot].get().isInstanced())) {
                    // non-instanced -> instanced
                    glext.glVertexAttribDivisorARB(slot, vb.getInstanceSpan());
                } else if (!vb.isInstanced() && attribs[slot] != null && attribs[slot].get() != null && attribs[slot].get().isInstanced()) {
                    // instanced -> non-instanced
                    glext.glVertexAttribDivisorARB(slot, 0);
                }
                attribs[slot] = vb.getWeakRef();
            }
        }
        if (debug && caps.contains(Caps.GLDebug)) {
            if (vb.getName() != null) glext.glObjectLabel(GLExt.GL_BUFFER, vb.getId(), vb.getName());
        }
    }

    public void setVertexAttrib(VertexBuffer vb) {
        setVertexAttrib(vb, null);
    }

    public void drawTriangleArray(Mesh.Mode mode, int count, int vertCount) {
        boolean useInstancing = count > 1 && caps.contains(Caps.MeshInstancing);
        if (useInstancing) {
            glext.glDrawArraysInstancedARB(convertElementMode(mode), 0,
                    vertCount, count);
        } else {
            gl.glDrawArrays(convertElementMode(mode), 0, vertCount);
        }
    }

    public void drawTriangleList(VertexBuffer indexBuf, Mesh mesh, int count) {
        if (indexBuf.getBufferType() != VertexBuffer.Type.Index) {
            throw new IllegalArgumentException("Only index buffers are allowed as triangle lists.");
        }

        switch (indexBuf.getFormat()) {
            case UnsignedByte:
            case UnsignedShort:
                // OK: Works on all platforms.
                break;
            case UnsignedInt:
                // Requires extension on OpenGL ES 2.
                if (!caps.contains(Caps.IntegerIndexBuffer)) {
                    throw new RendererException("32-bit index buffers are not supported by the video hardware");
                }
                break;
            default:
                // What is this?
                throw new RendererException("Unexpected format for index buffer: " + indexBuf.getFormat());
        }

        if (indexBuf.isUpdateNeeded()) {
            updateBufferData(indexBuf);
        }

        int bufId = indexBuf.getId();
        assert bufId != -1;

        if (context.boundElementArrayVBO != bufId) {
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, bufId);
            context.boundElementArrayVBO = bufId;
            //statistics.onVertexBufferUse(indexBuf, true);
        } else {
            //statistics.onVertexBufferUse(indexBuf, true);
        }

        int vertCount = mesh.getVertexCount();
        boolean useInstancing = count > 1 && caps.contains(Caps.MeshInstancing);

        if (mesh.getMode() == Mode.Hybrid) {
            int[] modeStart = mesh.getModeStart();
            int[] elementLengths = mesh.getElementLengths();

            int elMode = convertElementMode(Mode.Triangles);
            int fmt = convertFormat(indexBuf.getFormat());
            int elSize = indexBuf.getFormat().getComponentSize();
            int listStart = modeStart[0];
            int stripStart = modeStart[1];
            int fanStart = modeStart[2];
            int curOffset = 0;
            for (int i = 0; i < elementLengths.length; i++) {
                if (i == stripStart) {
                    elMode = convertElementMode(Mode.TriangleStrip);
                } else if (i == fanStart) {
                    elMode = convertElementMode(Mode.TriangleFan);
                }
                int elementLength = elementLengths[i];

                if (useInstancing) {
                    glext.glDrawElementsInstancedARB(elMode,
                            elementLength,
                            fmt,
                            curOffset,
                            count);
                } else {
                    gl.glDrawRangeElements(elMode,
                            0,
                            vertCount,
                            elementLength,
                            fmt,
                            curOffset);
                }

                curOffset += elementLength * elSize;
            }
        } else {
            if (useInstancing) {
                glext.glDrawElementsInstancedARB(convertElementMode(mesh.getMode()),
                        indexBuf.getData().limit(),
                        convertFormat(indexBuf.getFormat()),
                        0,
                        count);
            } else {
                gl.glDrawRangeElements(convertElementMode(mesh.getMode()),
                        0,
                        vertCount,
                        indexBuf.getData().limit(),
                        convertFormat(indexBuf.getFormat()),
                        0);
            }
        }
    }

    /*==============*\
    |* Render Calls *|
    \*==============*/

    /**
     * Convert a mesh mode to the corresponding GL value.
     *
     * @param mode input enum value (not null)
     * @return the corresponding GL value
     */
    public int convertElementMode(Mesh.Mode mode) {
        switch (mode) {
            case Points:
                return GL.GL_POINTS;
            case Lines:
                return GL.GL_LINES;
            case LineLoop:
                return GL.GL_LINE_LOOP;
            case LineStrip:
                return GL.GL_LINE_STRIP;
            case Triangles:
                return GL.GL_TRIANGLES;
            case TriangleFan:
                return GL.GL_TRIANGLE_FAN;
            case TriangleStrip:
                return GL.GL_TRIANGLE_STRIP;
            case Patch:
                return GL4.GL_PATCHES;
            default:
                throw new UnsupportedOperationException("Unrecognized mesh mode: " + mode);
        }
    }

    public void updateVertexArray(Mesh mesh, VertexBuffer instanceData) {
        int id = mesh.getId();
        if (id == -1) {
            IntBuffer temp = intBuf1;
            gl3.glGenVertexArrays(temp);
            id = temp.get(0);
            mesh.setId(id);
        }

        if (context.boundVertexArray != id) {
            gl3.glBindVertexArray(id);
            context.boundVertexArray = id;
        }

        VertexBuffer interleavedData = mesh.getBuffer(Type.InterleavedData);
        if (interleavedData != null && interleavedData.isUpdateNeeded()) {
            updateBufferData(interleavedData);
        }

        if (instanceData != null) {
            setVertexAttrib(instanceData, null);
        }

        for (VertexBuffer vb : mesh.getBufferList().getArray()) {
            if (vb.getBufferType() == Type.InterleavedData
                    || vb.getUsage() == Usage.CpuOnly // ignore cpu-only buffers
                    || vb.getBufferType() == Type.Index) {
                continue;
            }

            if (vb.getStride() == 0) {
                // not interleaved
                setVertexAttrib(vb);
            } else {
                // interleaved
                setVertexAttrib(vb, interleavedData);
            }
        }
    }

    private void renderMeshDefault(Mesh mesh, int lod, int count, VertexBuffer[] instanceData) {

        // Here while count is still passed in.  Can be removed when/if
        // the method is collapsed again.  -pspeed
        count = Math.max(mesh.getInstanceCount(), count);

        VertexBuffer interleavedData = mesh.getBuffer(Type.InterleavedData);
        if (interleavedData != null && interleavedData.isUpdateNeeded()) {
            updateBufferData(interleavedData);
        }

        VertexBuffer indices;
        if (mesh.getNumLodLevels() > 0) {
            indices = mesh.getLodLevel(lod);
        } else {
            indices = mesh.getBuffer(Type.Index);
        }

        if (instanceData != null) {
            for (VertexBuffer vb : instanceData) {
                setVertexAttrib(vb, null);
            }
        }

        for (VertexBuffer vb : mesh.getBufferList().getArray()) {
            if (vb.getBufferType() == Type.InterleavedData
                    || vb.getUsage() == Usage.CpuOnly // ignore cpu-only buffers
                    || vb.getBufferType() == Type.Index) {
                continue;
            }

            if (vb.getStride() == 0) {
                // not interleaved
                setVertexAttrib(vb);
            } else {
                // interleaved
                setVertexAttrib(vb, interleavedData);
            }
        }

        clearVertexAttribs();

        if (indices != null) {
            drawTriangleList(indices, mesh, count);
        } else {
            drawTriangleArray(mesh.getMode(), count, mesh.getVertexCount());
        }
    }

    @Override
    public void renderMesh(Mesh mesh, int lod, int count, VertexBuffer[] instanceData) {
        if (mesh.getVertexCount() == 0 || mesh.getTriangleCount() == 0 || count == 0) {
            return;
        }

        if (count > 1 && !caps.contains(Caps.MeshInstancing)) {
            throw new RendererException("Mesh instancing is not supported by the video hardware");
        }

        if (mesh.getLineWidth() != 1f && context.lineWidth != mesh.getLineWidth()) {
            gl.glLineWidth(mesh.getLineWidth());
            context.lineWidth = mesh.getLineWidth();
        }

        if (gl4 != null && mesh.getMode().equals(Mode.Patch)) {
            gl4.glPatchParameter(mesh.getPatchVertexCount());
        }
        statistics.onMeshDrawn(mesh, lod, count);
//        if (ctxCaps.GL_ARB_vertex_array_object){
//            renderMeshVertexArray(mesh, lod, count);
//        }else{
        renderMeshDefault(mesh, lod, count, instanceData);
//        }
    }

    @Override
    public void setMainFrameBufferSrgb(boolean enableSrgb) {
        // Gamma correction
        if (!caps.contains(Caps.Srgb) && enableSrgb) {
            // Not supported, sorry.
            logger.warning("sRGB framebuffer is not supported " +
                    "by video hardware, but was requested.");

            return;
        }

        setFrameBuffer(null);

        if (enableSrgb) {
            if (
                // Workaround: getBoolean(GLExt.GL_FRAMEBUFFER_SRGB_CAPABLE_EXT) causes error 1280 (invalid enum) on macos
                JmeSystem.getPlatform().getOs() != Platform.Os.MacOS
                && !getBoolean(GLExt.GL_FRAMEBUFFER_SRGB_CAPABLE_EXT)
            ) {
                logger.warning("Driver claims that default framebuffer " + "is not sRGB capable. Enabling anyway.");
            }

            gl.glEnable(GLExt.GL_FRAMEBUFFER_SRGB_EXT);

            logger.log(Level.FINER, "SRGB FrameBuffer enabled (Gamma Correction)");
        } else {
            gl.glDisable(GLExt.GL_FRAMEBUFFER_SRGB_EXT);
        }
    }

    @Override
    public void setLinearizeSrgbImages(boolean linearize) {
        if (caps.contains(Caps.Srgb)) {
            linearizeSrgbImages = linearize;
        }
    }

    @Override
    public int[] generateProfilingTasks(int numTasks) {
        IntBuffer ids = BufferUtils.createIntBuffer(numTasks);
        gl.glGenQueries(numTasks, ids);
        return BufferUtils.getIntArray(ids);
    }

    @Override
    public void startProfiling(int taskId) {
        gl.glBeginQuery(GL.GL_TIME_ELAPSED, taskId);
    }

    @Override
    public void stopProfiling() {
        gl.glEndQuery(GL.GL_TIME_ELAPSED);
    }

    @Override
    public long getProfilingTime(int taskId) {
        return gl.glGetQueryObjectui64(taskId, GL.GL_QUERY_RESULT);
    }

    @Override
    public boolean isTaskResultAvailable(int taskId) {
        return gl.glGetQueryObjectiv(taskId, GL.GL_QUERY_RESULT_AVAILABLE) == 1;
    }

    @Override
    public boolean getAlphaToCoverage() {
        if (caps.contains(Caps.Multisample)) {
            return gl.glIsEnabled(GLExt.GL_SAMPLE_ALPHA_TO_COVERAGE_ARB);

        }
        return false;
    }

    @Override
    public int getDefaultAnisotropicFilter() {
        return this.defaultAnisotropicFilter;
    }

    /**
     * Determine the maximum allowed width for lines.
     *
     * @return the maximum width (in pixels)
     */
    @Override
    public float getMaxLineWidth() {
        // Since neither JMonkeyEngine nor LWJGL ever enables GL_LINE_SMOOTH,
        // all lines are aliased, but just in case...
        assert !gl.glIsEnabled(GL.GL_LINE_SMOOTH);

        // When running with OpenGL 3.2+ core profile,
        // compatibility features such as multipixel lines aren't available.
        if (caps.contains(Caps.CoreProfile)) {
            return 1f;
        }

        floatBuf16.clear();
        gl.glGetFloat(GL.GL_ALIASED_LINE_WIDTH_RANGE, floatBuf16);
        float result = floatBuf16.get(1);

        return result;
    }

    /**
     * Test whether images with the sRGB flag will be linearized when read by a
     * shader.
     *
     * @return true for linearization, false for no linearization
     */
    @Override
    public boolean isLinearizeSrgbImages() {
        return linearizeSrgbImages;
    }

    /**
     * Test whether colors rendered to the main framebuffer undergo
     * linear-to-sRGB conversion.
     *
     * @return true for conversion, false for no conversion
     */
    @Override
    public boolean isMainFrameBufferSrgb() {
        if (!caps.contains(Caps.Srgb)) {
            return false;
        } else {
            return gl.glIsEnabled(GLExt.GL_FRAMEBUFFER_SRGB_EXT);
        }
    }
}
