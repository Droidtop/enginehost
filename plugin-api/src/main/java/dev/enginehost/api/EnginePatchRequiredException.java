package dev.enginehost.api;

/**
 * Thrown by a runtime module when a launch failed in the particular way
 * that means the game needs a compatibility patch it does not have.
 *
 * Engines fail in recognisable ways when content is encrypted or packed
 * for a variant they cannot read — KiriKiri's narrow-to-wide conversion
 * failure on an encrypted XP3 is the motivating case. The module's job is
 * to recognise its own failure signature and raise this. Everything after
 * that — explaining, letting the user choose a file, unpacking it, putting
 * it in place, retrying — is Enginehost's, because it is host policy
 * rather than engine behaviour.
 *
 * <h2>What the user is told versus what the host uses</h2>
 *
 * The person holding the device is told <em>"this game requires a
 * patch"</em> and nothing more. They do not need to learn that the answer
 * is called {@code xp3filter.tjs}, and making them learn it is the
 * failure mode this whole flow exists to avoid.
 *
 * {@link #requiredFile()} is separate from that: engines know what they
 * were looking for through their own patch-loading mechanism, and the host
 * uses that to place the supplied file correctly — the right name, the
 * right location, and the right entry to take out of an archive the user
 * picked. It is placement information, never a demand shown to the user.
 * A module that cannot determine it simply leaves it null, and the host
 * falls back to preserving whatever the user chose.
 *
 * <h2>Supply is always the user's own act</h2>
 *
 * Enginehost never fetches a patch. The user obtains the file however
 * they like and picks it explicitly; an in-app downloader would make
 * Enginehost the delivery vehicle for whatever a hostile host served,
 * executed inside the engine under Enginehost's own storage permission.
 * Nothing is auto-detected from Downloads either — a file becomes a patch
 * because somebody chose it, not because it looked like one.
 */
public class EnginePatchRequiredException extends Exception {

    private final String requiredFile;

    public EnginePatchRequiredException() {
        this(null, null);
    }

    /**
     * @param requiredFile what the engine's own patch loading was looking
     *                     for, when it knows. Used only for placement.
     */
    public EnginePatchRequiredException(String requiredFile) {
        this(requiredFile, null);
    }

    public EnginePatchRequiredException(String requiredFile, Throwable cause) {
        super("This game requires a patch", cause);
        this.requiredFile = requiredFile;
    }

    /** The engine-reported target name, or null when it could not tell. */
    public String requiredFile() {
        return requiredFile;
    }
}
