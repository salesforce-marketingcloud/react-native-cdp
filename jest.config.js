module.exports = {
  preset: '@react-native/jest-preset',
  modulePathIgnorePatterns: ['<rootDir>/lib/'],
  testMatch: ['**/__tests__/**/*.test.ts'],
  collectCoverageFrom: ['src/**/*.ts', '!src/**/__tests__/**'],
}
