package io.github.gcbond.openhc.pc

import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** 只给当前用户读写的文件：信任库、carInfo、视频输出都经这里落盘。 */
internal object PrivateFiles {
    /** 这个文件系统有没有 POSIX 权限位（Windows 没有）。 */
    private val POSIX: Boolean = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

    val OWNER_ONLY_DIR: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    val OWNER_ONLY_FILE: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /**
     * 原子写：先写同目录下的临时文件（0600）并刷到磁盘，再改名覆盖目标。
     * 中途失败时目标文件保持原样，临时文件删掉。
     */
    @Throws(IOException::class)
    fun writeAtomically(target: Path, bytes: ByteArray) {
        val dir = target.toAbsolutePath().parent ?: throw IOException("没有上级目录: $target")
        val temp = dir.resolve(".${target.fileName}.tmp-${System.nanoTime()}")
        try {
            val channel = if (POSIX) {
                FileChannel.open(
                    temp,
                    setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
                )
            } else {
                // Windows 没有 POSIX 权限位：带 posix 初始属性会抛 UnsupportedOperationException，
                // 首次配对就会报 SAVE_LTPK_FAILED(31)。
                FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            }
            channel.use { ch ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) ch.write(buffer)
                ch.force(true)
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            Files.deleteIfExists(temp)
            throw e
        }
        syncDirectory(dir)
    }

    /** 新建文件（0600）并返回输出流；文件已存在时失败，不覆盖。 */
    @Throws(IOException::class)
    fun createNew(target: Path): OutputStream = Channels.newOutputStream(
        if (POSIX) {
            FileChannel.open(
                target,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_ONLY_FILE),
            )
        } else {
            // Windows 没有 POSIX 权限位：只用 CREATE_NEW 保证不覆盖，靠用户目录本身的 ACL 保护。
            FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        },
    )

    /**
     * 准备只给当前用户用的目录：不存在就以 0700 新建；已存在则必须是当前用户的目录（不是符号链接）且权限正好 0700，
     * 否则报错，不擅自改权限。
     */
    @Throws(IOException::class)
    fun prepareDirectory(dir: Path) {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            if (!POSIX) {
                Files.createDirectories(dir)
                return
            }
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR))
            // 创建时的权限会被 umask 收窄，但不会放宽；这里再确认一次。
            Files.setPosixFilePermissions(dir, OWNER_ONLY_DIR)
            return
        }
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("$dir 不是目录（或是符号链接）")
        }
        requireOwner(dir)
        if (!POSIX) return
        val permissions = Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS)
        if (permissions != OWNER_ONLY_DIR) {
            throw IOException("$dir 的权限是 ${PosixFilePermissions.toString(permissions)}，必须是 rwx------（可执行 chmod 700）")
        }
    }

    /** 已存在的私有文件必须是当前用户的普通文件（不是符号链接）且权限正好 0600。 */
    @Throws(IOException::class)
    fun checkPrivateFile(file: Path) {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("$file 不是普通文件（或是符号链接）")
        }
        requireOwner(file)
        if (!POSIX) return
        val permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS)
        if (permissions != OWNER_ONLY_FILE) {
            throw IOException("$file 的权限是 ${PosixFilePermissions.toString(permissions)}，必须是 rw-------（可执行 chmod 600）")
        }
    }

    private fun requireOwner(path: Path) {
        val owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name
        val me = System.getProperty("user.name")
        // Windows 上 owner 是 "机器名\用户名"，只比较用户名那一段。
        val short = owner.substringAfterLast('\\')
        if (short != me && owner != me) throw IOException("$path 属于 $owner，不是当前用户 $me")
    }

    /** 改名之后把目录项也刷到磁盘；有的系统不支持打开目录，做不到就算了。 */
    private fun syncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // 尽力而为。
        }
    }
}
