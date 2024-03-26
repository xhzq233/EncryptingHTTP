## 准备

> AVD（Android Virtual Device）环境
> Android Studio+Android Emulator

1. 下载 Magisk，提供Zygisk用于注入代码在安卓进程，https://github.com/topjohnwu/Magisk/releases
2. 下载 LSPosed，Zygisk module，用于提供 hook 接口。原 Xposed 框架已经废弃，而该框架能支持到安卓 14，https://github.com/LSPosed/LSPosed/releases
3. （Required In AVD）Clone https://github.com/newbit1/rootAVD，用于 Root AVD， 替换 Kernel 等。

## 安装

任意API版本（推荐12）的 AVD，将你想要安装的 Magisk.apk，拖入 rootAVD 文件夹中，重命名并替换掉原本的Magisk.zip（不替换也行）。

接着 ./rootAVD.sh ListAllAVDs 查看当前可用 AVD，在此之前确保 platform_tools 和 $ANDROID_HOME 环境配置好。

在 AVD 启动情况下（记得在 Advanced Settings 里改成 Cold Boot），

```shell
./rootAVD.sh system-images/android-<API_VERSION>/google_apis_playstore/arm64-v8a/ramdisk.img
```

接着会死机，有个卡死的 qemu 进程会挡住 adb 5555 接口，需要 lsof -i tcp:5555 找到然后去 activity/task manager 手动退出，接下来启动 avd， Magisk 已经安装。

再安装 LSPosed 只需把压缩包 adb push 到 avd 里，使用 Magisk 安装模块。![img.png](img.png)

## Xposed开发

1. AS 新建 No Activity 工程
2. 根目录的 settings.gradle 添加 maven 仓库
   
   ```
   dependencyResolutionManagement {
   
    	...
    
      repositories {
   
          ...
   
          maven { url 'https://api.xposed.info/' }
      }
    }
   ```
   
3. build.gradle 添加依赖

   ``` 
   dependencies {
     compileOnly 'de.robv.android.xposed:api:82'
   
     ...
   }
   ```
   
4. 在 /app/src/main/assets 新建 xposed_init 入口文件，写入com.example.xposedmodule.<HOOK_CLASS_NAME>
5. AndroidManifest.xml 添加用于标识 xposed 模块
   ```xml
   <meta-data
   android:name="xposedmodule"
   android:value="true" />
   <meta-data
   android:name="xposeddescription"
   android:value="hook http" />
   <meta-data
   android:name="xposedminversion"
   android:value="54" />
   ```
6. 最后在 java/com/example/xposedmodule/<HOOK_CLASS_NAME>.kt 添加 Hook 代码