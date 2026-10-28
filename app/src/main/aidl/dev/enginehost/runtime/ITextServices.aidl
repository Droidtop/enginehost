package dev.enginehost.runtime;

import dev.enginehost.runtime.ITextTranslated;

/**
 * The runtime -> Enginehost's main process: the services a game's text gets that need what the
 * runtime may not have, the network and the person's keys (TextServicesService). The runtime
 * binds it once per launch; its calls are oneway, so a slow service never stalls the game.
 */
interface ITextServices {
    /** Translates one line (an ehtext/1 line event) of the game [game] (its folder); the answer comes to [answer]. */
    oneway void translate(String game, String line, ITextTranslated answer);
}
