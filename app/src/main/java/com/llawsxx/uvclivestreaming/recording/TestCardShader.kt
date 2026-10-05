package com.llawsxx.uvclivestreaming.recording

/** Procedural RGB cards, sampled in source-pixel coordinates; no full-frame CPU generation/upload. */
internal object TestCardShader {
    private val glyphs = listOf(
        intArrayOf(14,17,19,21,25,17,14), intArrayOf(4,12,4,4,4,4,14),
        intArrayOf(14,17,1,2,4,8,31), intArrayOf(30,1,1,14,1,1,30),
        intArrayOf(2,6,10,18,31,2,2), intArrayOf(31,16,16,30,1,1,30),
        intArrayOf(14,16,16,30,17,17,14), intArrayOf(31,1,2,4,8,8,8),
        intArrayOf(14,17,17,14,17,17,14), intArrayOf(14,17,17,15,1,1,14),
        intArrayOf(17,17,17,21,21,21,10), // W = 10
        intArrayOf(17,17,17,31,17,17,17), // H = 11
        intArrayOf(31,16,16,30,16,16,16), // F = 12
        intArrayOf(30,17,17,30,16,16,16), // P = 13
        intArrayOf(15,16,16,14,1,1,30),   // S = 14
        intArrayOf(31,4,4,4,4,4,4),      // T = 15
        intArrayOf(0,0,0,0,0,12,12),     // . = 16
    )
    val fragment: String = """
        precision highp float;
        varying vec2 vUv;
        uniform int uTestPattern;
        uniform vec4 uTestMode; // width, height, fps, frame modulo 1,000,000
        uniform float uTestSeconds;
        uniform bool uGradeEnabled;
        uniform sampler2D uColorLut;
        uniform vec4 uLutInfo;
        vec3 grade(vec3 rgb) {
            vec3 p = clamp(rgb, 0.0, 1.0) * (uLutInfo.x - 1.0);
            float lower = floor(p.b), upper = min(lower + 1.0, uLutInfo.x - 1.0);
            vec2 tile0 = vec2(mod(lower,uLutInfo.y),floor(lower/uLutInfo.y));
            vec2 tile1 = vec2(mod(upper,uLutInfo.y),floor(upper/uLutInfo.y));
            return mix(texture2D(uColorLut,(tile0*uLutInfo.x+p.rg+0.5)/uLutInfo.zw).rgb,
                texture2D(uColorLut,(tile1*uLutInfo.x+p.rg+0.5)/uLutInfo.zw).rgb,fract(p.b));
        }
        float glyphRow(float code,float row) {
    """ + glyphs.mapIndexed { i, rows ->
        "if(code == $i.0) { " + rows.mapIndexed { y, bits -> "if(row == $y.0) return $bits.0;" }.joinToString(" ") + " }"
    }.joinToString("\n") + """
            return 0.0;
        }
        float digit(float value,float place) { return mod(floor(value/pow(10.0,place)),10.0); }
        // W1920 H1080 060.00FPS F000000 T0000.000S
        float hudCode(float slot) {
            if(slot==0.0) return 10.0;
            if(slot>=1.0 && slot<=4.0) return digit(uTestMode.x,4.0-slot);
            if(slot==6.0) return 11.0;
            if(slot>=7.0 && slot<=10.0) return digit(uTestMode.y,10.0-slot);
            if(slot>=12.0 && slot<=14.0) return digit(floor(uTestMode.z*100.0+0.5),16.0-slot);
            if(slot==15.0) return 16.0;
            if(slot>=16.0 && slot<=17.0) return digit(floor(uTestMode.z*100.0+0.5),17.0-slot);
            if(slot==18.0) return 12.0;
            if(slot==19.0) return 13.0;
            if(slot==20.0) return 14.0;
            if(slot==22.0) return 12.0;
            if(slot>=23.0 && slot<=28.0) return digit(uTestMode.w,28.0-slot);
            if(slot==30.0) return 15.0;
            if(slot>=31.0 && slot<=34.0) return digit(floor(uTestSeconds*1000.0),37.0-slot);
            if(slot==35.0) return 16.0;
            if(slot>=36.0 && slot<=38.0) return digit(floor(uTestSeconds*1000.0),38.0-slot);
            if(slot==39.0) return 14.0;
            return -1.0;
        }
        vec3 bars(float x,float level) {
            float n=floor(clamp(x,0.0,0.99999)*8.0);
            if(n<1.0) return vec3(level);
            if(n<2.0) return vec3(level,level,0.0);
            if(n<3.0) return vec3(0.0,level,level);
            if(n<4.0) return vec3(0.0,level,0.0);
            if(n<5.0) return vec3(level,0.0,level);
            if(n<6.0) return vec3(level,0.0,0.0);
            if(n<7.0) return vec3(0.0,0.0,level);
            return vec3(0.0);
        }
        vec3 smpte(vec2 uv) {
            float n=floor(clamp(uv.x,0.0,0.99999)*7.0);
            if(uv.y<0.67) return bars(uv.x*7.0/8.0,0.75);
            if(uv.y<0.76) {
                if(n==0.0) return vec3(0.0,0.0,0.75);
                if(n==2.0) return vec3(0.75,0.0,0.75);
                if(n==4.0) return vec3(0.0,0.75,0.75);
                if(n==6.0) return vec3(0.75);
                return vec3(0.0);
            }
            if(uv.x<0.16) return vec3(0.0,0.25,0.45);
            if(uv.x<0.32) return vec3(1.0);
            if(uv.x<0.48) return vec3(0.2,0.0,0.4);
            if(uv.x<0.72) return vec3(0.0);
            return vec3(floor((uv.x-0.72)/0.09334)*0.035);
        }
        vec3 levels(vec2 uv) {
            if(uv.y<0.25) return vec3(floor(uv.x*16.0)/15.0);
            if(uv.y<0.5) return vec3(uv.x);
            if(uv.y<0.75) {
                float k=floor(uv.x*8.0);
                if(k<1.0) return vec3(0.0);
                if(k<2.0) return vec3(4.0/255.0);
                if(k<3.0) return vec3(8.0/255.0);
                if(k<4.0) return vec3(16.0/255.0);
                if(k<5.0) return vec3(235.0/255.0);
                if(k<6.0) return vec3(247.0/255.0);
                if(k<7.0) return vec3(251.0/255.0);
                return vec3(1.0);
            }
            return bars(uv.x,1.0)*uv.x;
        }
        vec3 resolution(vec2 uv) {
            vec2 p=floor(uv*uTestMode.xy);
            float section=floor(uv.x*4.0);
            float period=pow(2.0,section);
            if(uv.y<0.34) return vec3(mod(floor(p.x/period),2.0));
            if(uv.y<0.67) return vec3(mod(floor(p.y/period),2.0));
            if(uv.x<0.5) return vec3(mod(floor(p.x/period)+floor(p.y/period),2.0));
            float wedge=step(0.0,sin((uv.x-0.5)*(uv.x-0.5)*uTestMode.x*3.14159265));
            return vec3(wedge);
        }
        vec3 motion(vec2 uv) {
            float stepSize=max(2.0,floor(uTestMode.x/120.0));
            float center=mod(uTestMode.w*stepSize,uTestMode.x);
            vec2 p=floor(uv*uTestMode.xy);
            vec3 rgb=vec3(0.08);
            if(mod(p.x,stepSize*10.0)<1.0) rgb=vec3(0.45);
            if(abs(p.x-center)<stepSize*0.6) rgb=vec3(1.0,1.0,0.0);
            float phase=mod(uTestMode.w,60.0);
            if(uv.y>0.7 && floor(uv.x*60.0)==phase) rgb=vec3(0.0,1.0,0.2);
            if(uv.x>0.82 && uv.y<0.25) rgb=vec3(mod(uTestMode.w,2.0));
            return rgb;
        }
        vec3 composite(vec2 uv,float activeHeight) {
            vec2 p=(uv-0.5)*vec2(uTestMode.x/activeHeight,1.0);
            vec3 rgb=vec3(0.25);
            if(mod(floor(uv.x*32.0)+floor(uv.y*18.0),2.0)<1.0) rgb=vec3(0.32);
            float r=length(p);
            if(r<0.43) {
                if(uv.y<0.42) rgb=bars(uv.x,0.75);
                else if(uv.y<0.58) rgb=vec3(floor(uv.x*12.0)/11.0);
                else rgb=vec3(mod(floor(uv.x*uTestMode.x/2.0),2.0));
            }
            if(abs(r-0.43)<1.5/activeHeight || abs(p.x)<1.0/activeHeight || abs(p.y)<1.0/activeHeight) rgb=vec3(1.0);
            return rgb;
        }
        void main() {
            // Reserve a footer for source dimensions, fps, frame number and seconds.
            float scale=max(1.0,floor(min(uTestMode.x/250.0,uTestMode.y/180.0)));
            float footer=min(0.12,12.0*scale/uTestMode.y);
            vec3 rgb;
            if(vUv.y>1.0-footer) {
                vec2 q=(vUv*uTestMode.xy-vec2(4.0*scale,uTestMode.y-10.0*scale))/scale;
                float slot=floor(q.x/6.0), column=mod(floor(q.x),6.0), row=floor(q.y);
                float bits=glyphRow(hudCode(slot),row);
                float ink=mod(floor(bits/pow(2.0,4.0-column)),2.0);
                rgb=vec3(0.04);
                if(q.x>=0.0 && column<5.0 && row>=0.0 && row<7.0 && slot<40.0 && ink>0.5) rgb=vec3(1.0);
            } else {
                vec2 uv=vec2(vUv.x,vUv.y/(1.0-footer));
                if(uTestPattern==0) rgb=smpte(uv);
                else if(uTestPattern==1) rgb=bars(uv.x,0.75);
                else if(uTestPattern==2) rgb=bars(uv.x,1.0);
                else if(uTestPattern==3) rgb=levels(uv);
                else if(uTestPattern==4) rgb=resolution(vUv);
                else if(uTestPattern==5) rgb=motion(vUv);
                else rgb=composite(uv,uTestMode.y*(1.0-footer));
            }
            rgb=clamp(rgb,0.0,1.0);
            if(uGradeEnabled) rgb=grade(rgb);
            gl_FragColor=vec4(rgb,1.0);
        }
    """
}
