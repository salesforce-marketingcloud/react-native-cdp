require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "SdkDataCloudReactnativePlugin"
  s.version      = package['version']
  s.summary      = package['description']
  s.homepage     = package['homepage']
  s.license      = package['license']
  s.authors      = package['author']
  s.platforms    = { :ios => "15.1" }
  s.source       = { :git => "https://github.com/salesforce-marketingcloud/react-native-cdp.git", :tag => "#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift,cpp}"
  s.exclude_files = "ios/tests/**/*"
  s.requires_arc = true
  s.swift_versions = "5.0"

  if respond_to?(:install_modules_dependencies, true)
    install_modules_dependencies(s)
  else
    s.dependency 'React-Core'
  end
  s.dependency 'MarketingCloud-Cdp', '3.0.2'
end
