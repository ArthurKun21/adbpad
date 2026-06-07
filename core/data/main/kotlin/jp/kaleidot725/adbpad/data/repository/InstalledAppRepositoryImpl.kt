package jp.kaleidot725.adbpad.data.repository

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.malinskiy.adam.AndroidDebugBridgeClientFactory
import com.malinskiy.adam.Const
import com.malinskiy.adam.request.Feature
import com.malinskiy.adam.request.device.FetchDeviceFeaturesRequest
import com.malinskiy.adam.request.pkg.StreamingPackageInstallRequest
import com.malinskiy.adam.request.shell.v1.ShellCommandRequest
import com.malinskiy.adam.request.sync.AndroidFileType
import com.malinskiy.adam.request.sync.PullRequest
import com.malinskiy.adam.request.sync.PushRequest
import com.malinskiy.adam.request.sync.compat.CompatListFileRequest
import com.malinskiy.adam.request.sync.model.FileEntry
import jp.kaleidot725.adbpad.domain.model.app.AppDataDirectory
import jp.kaleidot725.adbpad.domain.model.app.AppFileEntry
import jp.kaleidot725.adbpad.domain.model.app.AppFilePreview
import jp.kaleidot725.adbpad.domain.model.app.InstalledApp
import jp.kaleidot725.adbpad.domain.model.device.Device
import jp.kaleidot725.adbpad.domain.repository.InstalledAppRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.createTempFile
import com.malinskiy.adam.request.shell.v2.ShellCommandRequest as ShellV2CommandRequest

class InstalledAppRepositoryImpl : InstalledAppRepository {
    private val adbClient = AndroidDebugBridgeClientFactory().build()

    override suspend fun getInstalledApps(device: Device): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            try {
                val result = adbClient.execute(ShellCommandRequest("pm list packages -3"), device.serial)
                if (result.exitCode != 0) return@withContext emptyList()

                result.output
                    .lineSequence()
                    .mapNotNull { it.toInstalledApp() }
                    .sortedBy { it.packageName.lowercase(Locale.getDefault()) }
                    .toList()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                emptyList()
            }
        }

    override suspend fun installPackage(
        device: Device,
        packageFile: File,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
                val result =
                    adbClient.execute(
                        StreamingPackageInstallRequest(
                            pkg = packageFile,
                            supportedFeatures = supportedFeatures,
                            reinstall = true,
                        ),
                        device.serial,
                    )
                result.success
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                false
            }
        }

    override suspend fun uninstallInstalledApp(
        device: Device,
        app: InstalledApp,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val result = adbClient.execute(ShellCommandRequest("pm uninstall ${app.packageName}"), device.serial)
                result.exitCode == 0 && !result.output.contains("Failure", ignoreCase = true)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                false
            }
        }

    override suspend fun getAppFiles(
        device: Device,
        app: InstalledApp,
        directory: AppDataDirectory,
    ): Result<List<AppFileEntry>, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val rootPath = getRootPath(app, directory)
                val files = listRemoteFiles(device, rootPath)
                Ok(files)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun getAppFileChildren(
        device: Device,
        directory: AppFileEntry.Directory,
    ): Result<List<AppFileEntry>, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val files = listRemoteFiles(device, directory.path)
                Ok(files)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun getAppFilePreview(
        device: Device,
        entry: AppFileEntry,
    ): Result<AppFilePreview, Exception> =
        withContext(Dispatchers.IO) {
            try {
                if (entry !is AppFileEntry.File) return@withContext Ok(AppFilePreview.Unsupported(entry))

                when {
                    entry.size == 0L && entry.isTextFile() -> Ok(AppFilePreview.Text(entry, ""))
                    entry.size == 0L -> Ok(AppFilePreview.Unsupported(entry))
                    entry.isImageFile() -> Ok(AppFilePreview.Image(entry, pullAppFile(device, entry)))
                    entry.isTextFile() -> Ok(AppFilePreview.Text(entry, pullAppFile(device, entry).readText()))
                    else -> Ok(AppFilePreview.Unsupported(entry))
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun saveAppFile(
        device: Device,
        entry: AppFileEntry.File,
        destination: File,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val target = prepareDestinationFile(destination)
                if (entry.size == 0L) {
                    target.outputStream().use { }
                } else {
                    pullAppFile(device, entry, target)
                }
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun overwriteAppFile(
        device: Device,
        source: File,
        destination: AppFileEntry.File,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                if (!source.isFile) throw IOException("${source.name} is not a file")

                val privateDataPath = destination.path.toPrivateDataPath()
                if (privateDataPath != null) {
                    pushPrivateDataFile(device, source, privateDataPath, destination.name)
                } else {
                    val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
                    val isPushed = adbClient.execute(PushRequest(source, destination.path, supportedFeatures), device.serial)
                    if (!isPushed) throw IOException("Failed to overwrite ${destination.name}")
                }
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun uploadAppFile(
        device: Device,
        source: File,
        destination: AppFileEntry,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                if (!source.isFile) throw IOException("${source.name} is not a file")

                val destinationPath =
                    when (destination) {
                        is AppFileEntry.Directory -> destination.path.resolveChildPath(source.name)
                        else -> destination.path
                    }
                val privateDataPath = destinationPath.toPrivateDataPath()
                if (privateDataPath != null) {
                    pushPrivateDataFile(device, source, privateDataPath, source.name)
                } else {
                    val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
                    val isPushed = adbClient.execute(PushRequest(source, destinationPath, supportedFeatures), device.serial)
                    if (!isPushed) throw IOException("Failed to upload ${source.name}")
                }
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun deleteAppFile(
        device: Device,
        entry: AppFileEntry,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val commandPrefix =
                    if (entry is AppFileEntry.Directory) {
                        "rm -rf"
                    } else {
                        "rm -f"
                    }
                val privateDataPath = entry.path.toPrivateDataPath()
                val result =
                    if (privateDataPath != null) {
                        executeRunAsCommand(
                            device = device,
                            packageName = privateDataPath.packageName,
                            command = "$commandPrefix ${privateDataPath.relativePath.toShellArgument()}",
                        )
                    } else {
                        adbClient
                            .execute(
                                ShellCommandRequest("$commandPrefix ${entry.path.toShellArgument()}"),
                                device.serial,
                            ).toShellOutput()
                    }

                if (result.exitCode != 0) throw IOException(result.errorMessage("Failed to delete ${entry.name}"))
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun renameAppFile(
        device: Device,
        entry: AppFileEntry,
        name: String,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val normalizedName = name.trim()
                if (normalizedName.isBlank() || normalizedName.contains("/")) {
                    throw IOException("Invalid name")
                }

                val parentPath = entry.parentPath() ?: throw IOException("Cannot rename ${entry.name}")
                val destinationPath = parentPath.resolveChildPath(normalizedName)
                val sourcePrivateDataPath = entry.path.toPrivateDataPath()
                val destinationPrivateDataPath = destinationPath.toPrivateDataPath()
                val result =
                    if (sourcePrivateDataPath != null) {
                        if (
                            destinationPrivateDataPath == null ||
                            sourcePrivateDataPath.packageName != destinationPrivateDataPath.packageName
                        ) {
                            throw IOException("Cannot rename ${entry.name}")
                        }

                        executeRunAsCommand(
                            device = device,
                            packageName = sourcePrivateDataPath.packageName,
                            command =
                                "mv ${sourcePrivateDataPath.relativePath.toShellArgument()} " +
                                    destinationPrivateDataPath.relativePath.toShellArgument(),
                        )
                    } else {
                        adbClient
                            .execute(
                                ShellCommandRequest(
                                    "mv ${entry.path.toShellArgument()} " +
                                        destinationPath.toShellArgument(),
                                ),
                                device.serial,
                            ).toShellOutput()
                    }

                if (result.exitCode != 0) throw IOException(result.errorMessage("Failed to rename ${entry.name}"))
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    override suspend fun createAppDirectory(
        device: Device,
        parent: AppFileEntry.Directory,
        name: String,
    ): Result<Unit, Exception> =
        withContext(Dispatchers.IO) {
            try {
                val directoryName = AppFileOperationCommand.normalizeFileName(name)
                val directoryPath = parent.path.resolveChildPath(directoryName)
                val privateDataPath = directoryPath.toPrivateDataPath()
                val result =
                    if (privateDataPath != null) {
                        executeRunAsCommand(
                            device = device,
                            packageName = privateDataPath.packageName,
                            command = AppFileOperationCommand.mkdirCommand(privateDataPath.relativePath),
                        )
                    } else {
                        adbClient
                            .execute(
                                ShellCommandRequest(AppFileOperationCommand.mkdirCommand(directoryPath)),
                                device.serial,
                            ).toShellOutput()
                    }

                if (result.exitCode != 0) {
                    throw IOException(result.errorMessage("Failed to create $directoryName"))
                }
                Ok(Unit)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                Err(exception)
            }
        }

    private suspend fun pullAppFile(
        device: Device,
        entry: AppFileEntry.File,
    ): File {
        val localFile = createPreviewFile(entry)
        pullAppFile(device, entry, localFile)
        return localFile
    }

    private suspend fun pullAppFile(
        device: Device,
        entry: AppFileEntry.File,
        localFile: File,
    ) {
        val privateDataPath = entry.path.toPrivateDataPath()
        if (privateDataPath != null) {
            pullPrivateDataFile(device, privateDataPath, entry, localFile)
            return
        }

        val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
        val isPulled = adbClient.execute(PullRequest(entry.path, localFile, supportedFeatures), device.serial)
        if (!isPulled) throw IOException("Failed to load ${entry.name}")
    }

    private suspend fun pullPrivateDataFile(
        device: Device,
        privateDataPath: PrivateDataPath,
        entry: AppFileEntry.File,
        localFile: File,
    ) {
        val result =
            executeRunAsCommand(
                device = device,
                packageName = privateDataPath.packageName,
                command = "cat ${privateDataPath.relativePath.toShellArgument()}",
                requireShellV2 = true,
            )
        if (result.exitCode != 0) throw IOException(result.errorMessage("Failed to load ${entry.name}"))
        localFile.outputStream().use { it.write(result.stdout) }
    }

    private suspend fun pushPrivateDataFile(
        device: Device,
        source: File,
        privateDataPath: PrivateDataPath,
        targetName: String,
    ) {
        val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
        val remoteTempFile = "/data/local/tmp/adbpad-${System.nanoTime()}-${source.name.toSafeRemoteFileName()}"
        try {
            val isPushed =
                adbClient.execute(
                    PushRequest(source, remoteTempFile, supportedFeatures, mode = "0644"),
                    device.serial,
                )
            if (!isPushed) throw IOException("Failed to upload ${source.name}")

            val result =
                executeRunAsCommand(
                    device = device,
                    packageName = privateDataPath.packageName,
                    command =
                        "cp ${remoteTempFile.toShellArgument()} " +
                            privateDataPath.relativePath.toShellArgument(),
                )
            if (result.exitCode != 0) throw IOException(result.errorMessage("Failed to upload $targetName"))
        } finally {
            adbClient.execute(ShellCommandRequest("rm -f ${remoteTempFile.toShellArgument()}"), device.serial)
        }
    }

    private fun createPreviewFile(entry: AppFileEntry.File): File {
        val extension = entry.extension()
        val suffix = if (extension.isBlank()) ".tmp" else ".$extension"
        return createTempFile(prefix = "adbpad-preview-", suffix = suffix)
            .toFile()
            .apply { deleteOnExit() }
    }

    private fun prepareDestinationFile(destination: File): File {
        if (destination.exists() && destination.isDirectory) {
            throw IOException("${destination.name} is a directory")
        }

        destination.parentFile?.mkdirs()
        if (!destination.exists() && !destination.createNewFile()) {
            throw IOException("Failed to create ${destination.name}")
        }

        return destination
    }

    private fun getRootPath(
        app: InstalledApp,
        directory: AppDataDirectory,
    ): String =
        when (directory) {
            AppDataDirectory.Data -> app.dataDir
            AppDataDirectory.SdCardData -> app.sdCardDataDir
        }

    private suspend fun listRemoteFiles(
        device: Device,
        directory: String,
    ): List<AppFileEntry> {
        val privateDataPath = directory.toPrivateDataPath()
        if (privateDataPath != null) {
            val result =
                executeRunAsCommand(
                    device = device,
                    packageName = privateDataPath.packageName,
                    command = "ls -la ${privateDataPath.relativePath.toShellArgument()}",
                )
            if (result.exitCode != 0) throw IOException(result.errorMessage("Failed to load $directory"))
            return result.output.toAppFileEntriesFromLs(directory)
        }

        val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
        return adbClient
            .execute(CompatListFileRequest(directory, supportedFeatures), device.serial)
            .let { it.toAppFileEntries(directory) }
    }

    private fun String.toInstalledApp(): InstalledApp? {
        val line = trim()
        if (!line.startsWith(PACKAGE_PREFIX)) return null

        return InstalledApp(packageName = line.removePrefix(PACKAGE_PREFIX))
    }

    private suspend fun executeRunAsCommand(
        device: Device,
        packageName: String,
        command: String,
        requireShellV2: Boolean = false,
    ): ShellOutput {
        val shellCommand = "run-as ${packageName.toShellArgument()} $command"
        val supportedFeatures = adbClient.execute(FetchDeviceFeaturesRequest(device.serial), device.serial)
        return if (supportedFeatures.contains(Feature.SHELL_V2)) {
            adbClient.execute(ShellV2CommandRequest(shellCommand), device.serial).toShellOutput()
        } else {
            if (requireShellV2) throw IOException("run-as file transfer requires shell v2")
            adbClient.execute(ShellCommandRequest(shellCommand), device.serial).toShellOutput()
        }
    }

    private fun ShellOutput.errorMessage(fallback: String): String =
        errorOutput
            .trim()
            .ifBlank { output.trim() }
            .ifBlank { fallback }

    private fun com.malinskiy.adam.request.shell.v1.ShellCommandResult.toShellOutput(): ShellOutput =
        ShellOutput(
            stdout = stdout,
            output = output,
            errorOutput = "",
            exitCode = exitCode,
        )

    private fun com.malinskiy.adam.request.shell.v2.ShellCommandResult.toShellOutput(): ShellOutput =
        ShellOutput(
            stdout = stdout,
            output = output,
            errorOutput = errorOutput,
            exitCode = exitCode,
        )

    private data class ShellOutput(
        val stdout: ByteArray,
        val output: String,
        val errorOutput: String,
        val exitCode: Int,
    )

    private fun AppFileEntry.parentPath(): String? =
        path
            .trimEnd('/')
            .substringBeforeLast('/', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }

    private fun AppFileEntry.File.isImageFile(): Boolean = extension() in IMAGE_FILE_EXTENSIONS

    private fun AppFileEntry.File.isTextFile(): Boolean {
        val normalizedName = name.lowercase(Locale.getDefault())
        return extension() in TEXT_FILE_EXTENSIONS || normalizedName in TEXT_FILE_NAMES
    }

    private fun AppFileEntry.File.extension(): String =
        name
            .substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.getDefault())

    companion object {
        private const val PACKAGE_PREFIX = "package:"
        private val IMAGE_FILE_EXTENSIONS = setOf("bmp", "gif", "jpeg", "jpg", "png", "webp")
        private val TEXT_FILE_EXTENSIONS =
            setOf(
                "cfg",
                "conf",
                "css",
                "csv",
                "gradle",
                "htm",
                "html",
                "ini",
                "java",
                "js",
                "json",
                "kt",
                "kts",
                "log",
                "md",
                "properties",
                "sh",
                "toml",
                "txt",
                "xml",
                "yaml",
                "yml",
            )
        private val TEXT_FILE_NAMES = setOf("changelog", "license", "notice", "readme")
    }
}

internal object AppFileOperationCommand {
    fun normalizeFileName(name: String): String {
        val normalizedName = name.trim()
        if (normalizedName.isBlank() || normalizedName.contains("/")) {
            throw IOException("Invalid name")
        }
        return normalizedName
    }

    fun mkdirCommand(path: String): String = "mkdir -p ${path.toShellArgument()}"
}

internal fun String.toPrivateDataPath(): PrivateDataPath? {
    if (!startsWith(DATA_DIRECTORY_PREFIX)) return null

    val withoutPrefix = removePrefix(DATA_DIRECTORY_PREFIX)
    val packageName = withoutPrefix.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
    val relativePath =
        withoutPrefix
            .substringAfter('/', missingDelimiterValue = ".")
            .ifBlank { "." }

    return PrivateDataPath(
        packageName = packageName,
        relativePath = relativePath,
    )
}

internal fun String.toAppFileEntriesFromLs(directory: String): List<AppFileEntry> =
    lineSequence()
        .mapNotNull { it.toAppFileEntryFromLs(directory) }
        .filterNot { it.name == "." || it.name == ".." }
        .sortedWith(appFileEntryComparator())
        .toList()

internal fun List<FileEntry>.toAppFileEntries(directory: String): List<AppFileEntry> =
    asSequence()
        .filter { it.exists() }
        .filterNot { it.name == "." || it.name == ".." || it.name == null }
        .map { it.toAppFileEntry(directory) }
        .sortedWith(appFileEntryComparator())
        .toList()

internal fun String.toShellArgument(): String = "'${replace("'", "'\\''")}'"

internal fun String.toSafeRemoteFileName(): String =
    replace(REMOTE_FILE_NAME_REGEX, "_")
        .ifBlank { "file" }

internal fun String.resolveChildPath(name: String): String =
    if (endsWith("/")) {
        "$this$name"
    } else {
        "$this/$name"
    }

internal data class PrivateDataPath(
    val packageName: String,
    val relativePath: String,
)

private fun String.toAppFileEntryFromLs(directory: String): AppFileEntry? {
    val match = LS_LINE_REGEX.matchEntire(trim()) ?: return null
    val permissions = match.groupValues[1]
    val size = match.groupValues[4].filter { it.isDigit() }.toLongOrNull() ?: 0L
    val date = match.groupValues[5]
    val time = match.groupValues[6]
    var name = match.groupValues[7]
    val type = permissions.firstOrNull().toAndroidFileType()

    if (type == AndroidFileType.SYMBOLIC_LINK) {
        name = name.substringBefore(" -> ").trim()
    }

    return toAppFileEntry(
        type = type,
        name = name,
        path = directory.resolveChildPath(name),
        permissions = permissions,
        size = size,
        date = date,
        time = time,
    )
}

private fun FileEntry.toAppFileEntry(directory: String): AppFileEntry {
    val name = requireNotNull(name)
    val path = directory.resolveChildPath(name)
    val dateTime = mtime.atZone(FILE_TIME_ZONE)
    return toAppFileEntry(
        type = type,
        name = name,
        path = path,
        permissions = mode.toPermissionString(),
        size = size().toLong(),
        date = FILE_DATE_FORMATTER.format(dateTime),
        time = FILE_TIME_FORMATTER.format(dateTime),
    )
}

private fun toAppFileEntry(
    type: AndroidFileType,
    name: String,
    path: String,
    permissions: String,
    size: Long,
    date: String,
    time: String,
): AppFileEntry =
    when (type) {
        AndroidFileType.DIRECTORY -> {
            AppFileEntry.Directory(
                name = name,
                path = path,
                permissions = permissions,
                size = size,
                date = date,
                time = time,
            )
        }

        AndroidFileType.REGULAR_FILE -> {
            AppFileEntry.File(
                name = name,
                path = path,
                permissions = permissions,
                size = size,
                date = date,
                time = time,
            )
        }

        AndroidFileType.SYMBOLIC_LINK -> {
            AppFileEntry.Link(
                name = name,
                path = path,
                permissions = permissions,
                size = size,
                date = date,
                time = time,
            )
        }

        else -> {
            AppFileEntry.Other(
                name = name,
                path = path,
                permissions = permissions,
                size = size,
                date = date,
                time = time,
            )
        }
    }

private val FileEntry.type: AndroidFileType
    get() =
        when {
            isDirectory() -> AndroidFileType.DIRECTORY
            isRegularFile() -> AndroidFileType.REGULAR_FILE
            isBlockDevice() -> AndroidFileType.BLOCK_SPECIAL_FILE
            isCharDevice() -> AndroidFileType.CHARACTER_SPECIAL_FILE
            isLink() -> AndroidFileType.SYMBOLIC_LINK
            mode.hasFileType(Const.FileType.S_IFIFO) -> AndroidFileType.FIFO
            mode.hasFileType(Const.FileType.S_IFSOCK) -> AndroidFileType.SOCKET_LINK
            else -> AndroidFileType.OTHER
        }

private fun UInt.hasFileType(fileType: UInt): Boolean = (this and Const.FileType.S_IFMT) == fileType

private fun Char?.toAndroidFileType(): AndroidFileType =
    when (this) {
        '-' -> AndroidFileType.REGULAR_FILE
        'b' -> AndroidFileType.BLOCK_SPECIAL_FILE
        'c' -> AndroidFileType.CHARACTER_SPECIAL_FILE
        'd' -> AndroidFileType.DIRECTORY
        'l' -> AndroidFileType.SYMBOLIC_LINK
        'p' -> AndroidFileType.FIFO
        's' -> AndroidFileType.SOCKET_LINK
        else -> AndroidFileType.OTHER
    }

private fun UInt.toPermissionString(): String {
    val mode = toInt()
    return buildString {
        append(mode.toFileTypeChar())
        appendReadWriteExecute(mode, OWNER_READ, OWNER_WRITE, OWNER_EXECUTE, SET_UID, 's', 'S')
        appendReadWriteExecute(mode, GROUP_READ, GROUP_WRITE, GROUP_EXECUTE, SET_GID, 's', 'S')
        appendReadWriteExecute(mode, OTHER_READ, OTHER_WRITE, OTHER_EXECUTE, STICKY, 't', 'T')
    }
}

private fun StringBuilder.appendReadWriteExecute(
    mode: Int,
    readMask: Int,
    writeMask: Int,
    executeMask: Int,
    specialMask: Int,
    specialExecuteChar: Char,
    specialOnlyChar: Char,
) {
    append(if (mode hasMode readMask) 'r' else '-')
    append(if (mode hasMode writeMask) 'w' else '-')
    append(
        when {
            mode hasMode specialMask -> if (mode hasMode executeMask) specialExecuteChar else specialOnlyChar
            mode hasMode executeMask -> 'x'
            else -> '-'
        },
    )
}

private fun Int.toFileTypeChar(): Char =
    when (toUInt() and Const.FileType.S_IFMT) {
        Const.FileType.S_IFDIR -> 'd'
        Const.FileType.S_IFREG -> '-'
        Const.FileType.S_IFBLK -> 'b'
        Const.FileType.S_IFCHR -> 'c'
        Const.FileType.S_IFLNK -> 'l'
        Const.FileType.S_IFIFO -> 'p'
        Const.FileType.S_IFSOCK -> 's'
        else -> '?'
    }

private infix fun Int.hasMode(mask: Int): Boolean = this and mask != 0

private fun appFileEntryComparator(): Comparator<AppFileEntry> =
    compareByDescending<AppFileEntry> { it.isDirectory }
        .thenBy { it.name.lowercase(Locale.getDefault()) }

private const val DATA_DIRECTORY_PREFIX = "/data/data/"
private const val OWNER_READ = 0x100
private const val OWNER_WRITE = 0x80
private const val OWNER_EXECUTE = 0x40
private const val GROUP_READ = 0x20
private const val GROUP_WRITE = 0x10
private const val GROUP_EXECUTE = 0x8
private const val OTHER_READ = 0x4
private const val OTHER_WRITE = 0x2
private const val OTHER_EXECUTE = 0x1
private const val SET_UID = 0x800
private const val SET_GID = 0x400
private const val STICKY = 0x200
private val FILE_TIME_ZONE = ZoneId.systemDefault()
private val FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
private val REMOTE_FILE_NAME_REGEX = Regex("[^A-Za-z0-9._-]")
private val LS_LINE_REGEX =
    Regex(
        "^([bcdlsp-][-r][-w][-xsS][-r][-w][-xsS][-r][-w][-xstST])\\s+" +
            "(?:\\d+\\s+)?(\\S+)\\s+(\\S+)\\s+([\\d\\s,]*)\\s+" +
            "(\\d{4}-\\d\\d-\\d\\d)\\s+(\\d\\d:\\d\\d)\\s+(.*)$",
    )
