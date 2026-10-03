package com.echobharat.models

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Properties

/**
 * One immutable voice generation is selected by one atomic metadata replacement.
 * Downloads and imports never overwrite the active generation. The old files are retained
 * even after successful activation, until the user explicitly removes the pack.
 * No Android dependencies: interruption/validation/rollback tests use real temporary files.
 */
class VoicePackFiles(private val root: File, private val externalRoot: File? = null) {
    data class Selection(val files: Map<ModelRole, File>, val fingerprint: String)

    companion object {
        val roles = setOf(ModelRole.TTS_ACOUSTIC, ModelRole.TTS_VOCODER, ModelRole.TTS_TOKENS)
        private val lock = Any()

        fun fingerprint(spec: LanguageModelSpec): String {
            val body = "${spec.packVersion}\n" + spec.ttsSpecs.sortedBy { it.role.name }
                .joinToString("\n") { "${it.role}:${it.fileName}:${it.sha256}" }
            return MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }

        fun matches(file: File, spec: ModelSpec): Boolean = file.isFile &&
            spec.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
            (spec.sizeBytes <= 0 || file.length() == spec.sizeBytes) &&
            VerifiedDownloader.sha256(file).equals(spec.sha256, ignoreCase = true)

        private fun safeName(name: String): Boolean =
            name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name
    }

    private fun directory(spec: LanguageModelSpec): File {
        require(safeName(spec.lang)) { "Invalid language directory" }
        require((spec.models + spec.legacyModels).all { safeName(it.fileName) }) { "Invalid model filename" }
        return File(root, spec.lang)
    }

    /** Resumable candidates live apart from every currently loadable file. */
    fun candidateDir(spec: LanguageModelSpec): File = File(directory(spec), ".voices/${fingerprint(spec)}")

    private fun stateFile(spec: LanguageModelSpec) = File(directory(spec), ".voice-state.properties")

    private fun state(spec: LanguageModelSpec): Properties = Properties().apply {
        val file = stateFile(spec)
        if (file.isFile) runCatching { file.inputStream().use(::load) }
    }

    private fun save(spec: LanguageModelSpec, value: Properties) {
        val target = stateFile(spec)
        target.parentFile!!.mkdirs()
        val staged = File.createTempFile("voice-state-", ".part", target.parentFile)
        try {
            FileOutputStream(staged).use { out ->
                value.store(out, "Verified voice generation")
                out.fd.sync()
            }
            Files.move(staged.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { staged.delete() }
    }

    private fun selection(dir: File?, models: List<ModelSpec>, verify: Boolean): Selection? {
        if (dir == null || models.map { it.role }.toSet() != roles || models.size != roles.size) return null
        val files = models.associate { it.role to File(dir, it.fileName) }
        if (!models.all { model ->
                val file = files.getValue(model.role)
                if (verify) matches(file, model) else file.isFile && file.length() > 0
            }) return null
        return Selection(files, dir.absolutePath + ":" + models.joinToString { "${it.fileName}:${it.sha256}" })
    }

    /** Resolves an entire pair plus tokens together; never mixes generations or roots. */
    fun resolve(spec: LanguageModelSpec, verify: Boolean = true): Selection? = synchronized(lock) {
        val state = state(spec)
        val active = state.getProperty("generation")
        val known = spec.ttsSpecs + spec.legacyModels.filter { it.role in roles }
        if (active != null && active.matches(Regex("[0-9a-f]{64}"))) {
            val recorded = roles.mapNotNull { role ->
                known.firstOrNull {
                    it.role == role && it.fileName == state.getProperty("${role}.name") &&
                        it.sha256 == state.getProperty("${role}.sha256")
                }
            }
            selection(File(directory(spec), ".voices/$active"), recorded, verify)?.let { return@synchronized it }
        }
        // Pre-migration installs and explicit development sideloads remain usable.
        val roots = listOfNotNull(directory(spec), externalRoot?.let { File(it, spec.lang) })
        // Unchanged token tables need not be duplicated in legacyModels. Complete each
        // historical pair with the currently published token spec when no old token exists.
        val legacy = roles.mapNotNull { role ->
            spec.legacyModels.firstOrNull { it.role == role } ?: spec.ttsSpecs.firstOrNull { it.role == role }
        }
        for (models in listOf(spec.ttsSpecs, legacy)) {
            for (dir in roots) selection(dir, models, verify)?.let { return@synchronized it }
        }
        null
    }

    fun hasExistingVoice(spec: LanguageModelSpec): Boolean = synchronized(lock) {
        if (stateFile(spec).isFile) return@synchronized true
        val models = spec.ttsSpecs + spec.legacyModels.filter { it.role in roles }
        listOfNotNull(directory(spec), externalRoot?.let { File(it, spec.lang) }).any { dir ->
            models.any { File(dir, it.fileName).isFile }
        }
    }

    fun isCurrent(spec: LanguageModelSpec): Boolean = synchronized(lock) {
        val current = state(spec)
        val installed = current.getProperty("packVersion")?.toIntOrNull()?.let { version ->
            VoicePackUpgrade.Installed(version, roles.mapNotNull { role ->
                val name = current.getProperty("${role}.name") ?: return@mapNotNull null
                name to current.getProperty("${role}.sha256", "")
            }.toMap())
        }
        if (VoicePackUpgrade.needsUpdate(spec.packVersion, spec.ttsSpecs.associate { it.fileName to it.sha256 },
                installed, hasReadableLegacyFiles = true) ||
            current.getProperty("generation") != fingerprint(spec)) return@synchronized false
        selection(candidateDir(spec), spec.ttsSpecs, verify = true) != null
    }

    fun needsUpdate(spec: LanguageModelSpec): Boolean = hasExistingVoice(spec) && !isCurrent(spec)

    fun needsVoiceRepair(spec: LanguageModelSpec): Boolean = synchronized(lock) {
        state(spec).getProperty("loadFailed") == "true"
    }

    fun recordLoadFailure(spec: LanguageModelSpec) = synchronized(lock) {
        save(spec, state(spec).apply { setProperty("loadFailed", "true") })
    }

    fun clearLoadFailure(spec: LanguageModelSpec) = synchronized(lock) {
        if (needsVoiceRepair(spec)) save(spec, state(spec).apply { remove("loadFailed") })
    }

    fun candidatesReady(spec: LanguageModelSpec): Boolean =
        selection(candidateDir(spec), spec.ttsSpecs, verify = true) != null

    /** Copy already verified flat/sideloaded files into the candidate without touching originals. */
    fun seedCandidates(spec: LanguageModelSpec) = synchronized(lock) {
        val dest = candidateDir(spec).apply { mkdirs() }
        for (model in spec.ttsSpecs) {
            val target = File(dest, model.fileName)
            if (matches(target, model)) continue
            val roots = listOfNotNull(directory(spec), externalRoot?.let { File(it, spec.lang) })
            val sourceNames = buildList {
                add(model.fileName)
                spec.legacyModels.filter { it.role == model.role }.forEach { add(it.fileName) }
            }
            val source = roots.flatMap { root -> sourceNames.map { File(root, it) } }
                .firstOrNull { candidate ->
                    val sourceSpec = listOf(model) + spec.legacyModels
                        .filter { it.role == model.role }
                    sourceSpec.any { it.fileName == candidate.name && matches(candidate, it) }
                } ?: continue
            val tmp = File.createTempFile("seed-", ".part", dest)
            try {
                source.copyTo(tmp, overwrite = true)
                check(matches(tmp, model)) { "Voice changed during staging" }
                Files.move(tmp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { tmp.delete() }
        }
    }

    /**
     * Caller serializes installation and holds the TTS use lock while validating and
     * activating. Hash proof is repeated after native load; a failed validator, move or
     * interrupted download never changes the active pointer or deletes the old files.
     */
    fun activate(spec: LanguageModelSpec, appVersion: Int, validate: (Selection) -> Boolean): Boolean = synchronized(lock) {
        val candidate = selection(candidateDir(spec), spec.ttsSpecs, verify = true) ?: return@synchronized false
        if (!validate(candidate)) return@synchronized false
        if (!candidatesReady(spec)) return@synchronized false
        val value = Properties().apply {
            setProperty("generation", fingerprint(spec))
            setProperty("packVersion", spec.packVersion.toString())
            setProperty("appVersionCode", appVersion.toString())
            spec.ttsSpecs.forEach {
                setProperty("${it.role}.name", it.fileName)
                setProperty("${it.role}.sha256", it.sha256)
            }
        }
        save(spec, value)
        true
    }
}
