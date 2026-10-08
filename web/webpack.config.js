/**
 * Bundles the Perspective component into a single UMD library + CSS file, written straight into
 * build/generated-resources/mounted/ (the :web Gradle project adds that folder to its jar, and the gateway
 * serves it at /res/voicetranscription/).
 */
const path = require("path");
const MiniCssExtractPlugin = require("mini-css-extract-plugin");

const LIB_NAME = "VoiceTranscription";

module.exports = (env, argv) => ({
    mode: argv.mode || "production",
    entry: { [LIB_NAME]: path.join(__dirname, "src/index.ts") },
    output: {
        library: { name: LIB_NAME, type: "umd" },
        path: path.resolve(__dirname, "build/generated-resources/mounted"),
        filename: "[name].js",
        clean: true,
    },
    devtool: argv.mode === "development" ? "source-map" : false,
    resolve: { extensions: [".tsx", ".ts", ".js", ".scss"] },
    module: {
        rules: [
            { test: /\.tsx?$/, use: "ts-loader", exclude: /node_modules/ },
            {
                test: /\.s?css$/,
                use: [MiniCssExtractPlugin.loader, { loader: "css-loader", options: { url: false } }, "sass-loader"],
            },
        ],
    },
    plugins: [new MiniCssExtractPlugin({ filename: "[name].css" })],
    // Provided by the Perspective runtime in the browser; bundling a second React would break hooks/context.
    externals: {
        react: "React",
        "react-dom": "ReactDOM",
        mobx: "mobx",
        "mobx-react": "mobxReact",
        "@inductiveautomation/perspective-client": "PerspectiveClient",
    },
    performance: { hints: false },
});
