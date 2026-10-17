package com.alananasss.kittytune.ui.player.lyrics.accompanist

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable

/** Keep punctuation-only timing tokens with the word they close when wrapping lyrics. */
fun groupIntoWords(syllables: List<KaraokeSyllable>): List<List<KaraokeSyllable>> {
    val words = mutableListOf<MutableList<KaraokeSyllable>>()
    var currentWord = mutableListOf<KaraokeSyllable>()
    for (syllable in syllables) {
        if (currentWord.isEmpty() && words.isNotEmpty() && syllable.content.isClosingLyricPunctuation()) {
            words.last().add(syllable)
            continue
        }
        currentWord.add(syllable)
        if (syllable.content.lastOrNull()?.isWhitespace() == true) {
            words.add(currentWord)
            currentWord = mutableListOf()
        }
    }
    if (currentWord.isNotEmpty()) words.add(currentWord)
    return words
}

internal fun String.isClosingLyricPunctuation(): Boolean {
    val content = trim()
    return content.isNotEmpty() && content.all { character ->
        character in ",.;:!?…。，、；：！？%‰°" ||
            Character.getType(character) == Character.END_PUNCTUATION.toInt() ||
            Character.getType(character) == Character.FINAL_QUOTE_PUNCTUATION.toInt()
    }
}
