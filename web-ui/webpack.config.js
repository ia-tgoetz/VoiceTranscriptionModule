/**
 * Builds the gateway settings page as a UMD library. The 8.3 gateway web UI loads it with SystemJS (see
 * GatewayHook: new SystemJsModule(..., "/res/voicetranscription/web-ui/voicetranscription-config.js")) and
 * renders the exported React component named in NavigationModel...mount(path, "VoiceTranscriptionSettings", module).
 * React is supplied by the gateway web UI, so it must stay external.
 */
const path = require("path");

module.exports = (env, argv) => ({
    mode: argv.mode || "production",
    entry: { "voicetranscription-config": path.join(__dirname, "src/index.ts") },
    output: {
        library: { name: "voicetranscription-config", type: "umd", umdNamedDefine: true },
        path: path.resolve(__dirname, "build/generated-resources/mounted/web-ui"),
        filename: "[name].js",
        publicPath: "",
        clean: true,
    },
    devtool: argv.mode === "development" ? "source-map" : false,
    resolve: { extensions: [".tsx", ".ts", ".js"] },
    module: { rules: [{ test: /\.tsx?$/, use: "ts-loader", exclude: /node_modules/ }] },
    externals: ["react", "react-dom"],
    performance: { hints: false },
});
