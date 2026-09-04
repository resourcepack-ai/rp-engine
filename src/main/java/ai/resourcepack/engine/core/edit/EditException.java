package ai.resourcepack.engine.core.edit;

/**
 * A refusal, carrying the sentence to print to whoever typed the command.
 *
 * <p>Its own type rather than a null or a boolean, because every one of these
 * ends up in somebody's chat window and "something went wrong" is not worth
 * printing. Two sources feed it and both are already written for a server
 * owner: this plugin's own checks ("chair uses a model this plugin cannot
 * find"), and studio's, which arrive as prose in the response body and are
 * passed through unchanged.
 */
final class EditException extends Exception {

    private static final long serialVersionUID = 1L;

    EditException(String message) {
        super(message);
    }
}
