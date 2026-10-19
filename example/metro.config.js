const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const path = require('node:path');

const repositoryRoot = path.resolve(__dirname, '..');

module.exports = mergeConfig(getDefaultConfig(__dirname), {
  watchFolders: [repositoryRoot],
});
