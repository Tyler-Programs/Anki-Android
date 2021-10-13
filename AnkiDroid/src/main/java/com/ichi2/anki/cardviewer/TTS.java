package com.ichi2.anki.cardviewer;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.android.material.snackbar.Snackbar;
import com.ichi2.anki.AnkiActivity;
import com.ichi2.anki.LanguageUtils;
import com.ichi2.anki.MetaDB;
import com.ichi2.anki.R;
import com.ichi2.anki.ReadText;
import com.ichi2.anki.TtsParser;
import com.ichi2.anki.UIUtils;
import com.ichi2.libanki.Card;
import com.ichi2.libanki.Sound;
import com.ichi2.anki.AbstractFlashcardViewer;

import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

import androidx.annotation.NonNull;
import timber.log.Timber;

/**
 * Wrapper for ReadText. TTS calls should be directed through this class.
 */
public class TTS {
    public final ReadText mReadText = new ReadText();
    private TextToSpeech mTTS;
    private String mTextToSpeak;
    private Sound.SoundSide mQuestionAnswer;
    public WeakReference<Context> mReviewer;
    private long mDid;
    private int mOrd;
    private final ArrayList<Locale> availableTtsLocales = new ArrayList<>();
    private Card mCurrentCard;

    /**
     * @param card The card to check the type of before determining the ordinal.
     * @return The card ordinal. If it's a Cloze card, returns 0.
     */
    private int getOrdUsingCardType(Card card) {
        if (card.model().isCloze()) {
            return 0;
        } else {
            return card.getOrd();
        }
    }

    /**
     * Returns the deck ID of the given {@link Card}.
     *
     * @param card The {@link Card} to get the deck ID
     * @return The deck ID of the {@link Card}
     */
    private long getDeckIdForCard(final Card card) {
        // Try to get the configuration by the original deck ID (available in case of a cram deck),
        // else use the direct deck ID (in case of a 'normal' deck.
        return card.getODid() == 0 ? card.getDid() : card.getODid();
    }

    /**
     * Returns true if the TTS engine supports the language of the locale represented by localeCode
     * (which should be in the format returned by Locale.toString()), false otherwise.
     */
    private boolean isLanguageAvailable(String localeCode) {
        return mTTS.isLanguageAvailable(LanguageUtils.localeFromStringIgnoringScriptAndExtensions(localeCode)) >=
                TextToSpeech.LANG_AVAILABLE;
    }

    public void buildAvailableLanguages() {
        availableTtsLocales.clear();
        Locale[] systemLocales = Locale.getAvailableLocales();
        availableTtsLocales.ensureCapacity(systemLocales.length);
        for (Locale loc : systemLocales) {
            try {
                int retCode = mTTS.isLanguageAvailable(loc);
                if (retCode >= TextToSpeech.LANG_COUNTRY_AVAILABLE) {
                    availableTtsLocales.add(loc);
                } else {
                    Timber.v("ReadText.buildAvailableLanguages() :: %s  not available (error code %d)", loc.getDisplayName(), retCode);
                }
            } catch (IllegalArgumentException e) {
                Timber.w(e, "Error checking if language %s available", loc.getDisplayName());
            }
        }
    }

    public void initializeTTS(Context context,  @NonNull AbstractFlashcardViewer.ReadTextListener listener) {
        // Store weak reference to Activity to prevent memory leak
        mReviewer = new WeakReference<>(context);
        // Create new TTS object and setup its onInit Listener
        mTTS = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // build list of available languages
                buildAvailableLanguages();
                if (!availableTtsLocales.isEmpty()) {
                    // notify the reviewer that TTS has been initialized
                    Timber.d("TTS initialized and available languages found");
                    ((AbstractFlashcardViewer) mReviewer.get()).ttsInitialized();
                } else {
                    UIUtils.showThemedToast(mReviewer.get(), mReviewer.get().getString(R.string.no_tts_available_message), false);
                    Timber.w("TTS initialized but no available languages found");
                }
                mTTS.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onDone(String arg0) {
                        listener.onDone();
                    }
                    @Override
                    @Deprecated
                    public void onError(String utteranceId) {
                        Timber.v("Andoid TTS failed. Check logcat for error. Indicates a problem with Android TTS engine.");

                        final Uri helpUrl = Uri.parse(mReviewer.get().getString(R.string.link_faq_tts));
                        final AnkiActivity ankiActivity = (AnkiActivity) mReviewer.get();
                        ankiActivity.mayOpenUrl(helpUrl);
                        UIUtils.showSnackbar(ankiActivity, R.string.no_tts_available_message, false, R.string.help,
                                v -> openTtsHelpUrl(helpUrl), ankiActivity.findViewById(R.id.root_layout),
                                new Snackbar.Callback());
                    }
                    @Override
                    public void onStart(String arg0) {
                        // no nothing
                    }
                });
            } else {
                UIUtils.showThemedToast(mReviewer.get(), mReviewer.get().getString(R.string.no_tts_available_message), false);
                Timber.w("TTS not successfully initialized");
            }
        });
        // Show toast that it's getting initialized, as it can take a while before the sound plays the first time
        UIUtils.showThemedToast(context, context.getString(R.string.initializing_tts), false);
    }

    public void readCardText(Context context, final Card card, final Sound.SoundSide cardSide) {
        final String cardSideContent;
        mCurrentCard = card;

        if (Sound.SoundSide.QUESTION == cardSide) {
            cardSideContent = card.q(true);
        } else if (Sound.SoundSide.ANSWER == cardSide) {
            cardSideContent = card.getPureAnswer();
        } else {
            Timber.w("Unrecognised cardSide");
            return;
        }
        String clozeReplacement = context.getString(R.string.reviewer_tts_cloze_spoken_replacement);
        readCardSide(cardSide, cardSideContent, getDeckIdForCard(card), getOrdUsingCardType(card), clozeReplacement);
    }

    /**
     * Read the given text using an appropriate TTS voice.
     * <p>
     * The voice is chosen as follows:
     * <p>
     * 1. If localeCode is a non-empty string representing a locale in the format returned
     * by Locale.toString(), and a voice matching the language of this locale (and ideally,
     * but not necessarily, also the country and variant of the locale) is available, then this
     * voice is used.
     * 2. Otherwise, if the database contains a saved language for the given 'did', 'ord' and 'qa'
     * arguments, and a TTS voice matching that language is available, then this voice is used
     * (unless the saved language is NO_TTS, in which case the text is not read at all).
     * 3. Otherwise, the user is asked to select a language from among those for which a voice is
     * available.
     *
     * @param queueMode TextToSpeech.QUEUE_ADD or TextToSpeech.QUEUE_FLUSH.
     */
    private void textToSpeech(String text, long did, int ord, Sound.SoundSide qa, String localeCode,
                                     int queueMode) {
        mTextToSpeak = text;
        mQuestionAnswer = qa;
        mDid = did;
        mOrd = ord;
        Timber.d("ReadText.textToSpeech() method started for string '%s', locale '%s'", text, localeCode);

        final String originalLocaleCode = localeCode;

        if (!localeCode.isEmpty()) {
            if (!isLanguageAvailable(localeCode)) {
                localeCode = "";
            }
        }
        if (localeCode.isEmpty()) {
            // get the user's existing language preference
            localeCode = getLanguage(mDid, mOrd, mQuestionAnswer);
            Timber.d("ReadText.textToSpeech() method found language choice '%s'", localeCode);
        }

        if (localeCode.equals(mReadText.NO_TTS)) {
            // user has chosen not to read the text
            return;
        }
        if (!localeCode.isEmpty() && isLanguageAvailable(localeCode)) {
            speak(mTextToSpeak, localeCode, queueMode);
            return;
        }

        // Otherwise ask the user what language they want to use
        if (!originalLocaleCode.isEmpty()) {
            // (after notifying them first that no TTS voice was found for the locale
            // they originally requested)
            UIUtils.showThemedToast(mReviewer.get(), mReviewer.get().getString(R.string.no_tts_available_message)
                    + " (" + originalLocaleCode + ")", false);
        }
        selectTts(mCurrentCard, mQuestionAnswer);
    }

    /**
     * Ask the user what language they want.
     *
     * @param card The card to choose the language for
     * @param qa   The card question or card answer
     */
    public void selectTts(Card card, Sound.SoundSide qa) {
        //TODO: Consolidate with ReadText.readCardSide
        Resources res = mReviewer.get().getResources();
        final MaterialDialog.Builder builder = new MaterialDialog.Builder(mReviewer.get());
        // Build the language list if it's empty
        if (availableTtsLocales.isEmpty()) {
            buildAvailableLanguages();
        }
        if (availableTtsLocales.isEmpty()) {
            Timber.w("ReadText.textToSpeech() no TTS languages available");
            builder.content(res.getString(R.string.no_tts_available_message))
                    .iconAttr(R.attr.dialogErrorIcon)
                    .positiveText(R.string.dialog_ok);
        } else {
            ArrayList<CharSequence> dialogItems = new ArrayList<>(availableTtsLocales.size());
            final ArrayList<String> dialogIds = new ArrayList<>(availableTtsLocales.size());
            // Add option: "no tts"
            dialogItems.add(res.getString(R.string.tts_no_tts));
            dialogIds.add(mReadText.NO_TTS);
            for (int i = 0; i < availableTtsLocales.size(); i++) {
                dialogItems.add(availableTtsLocales.get(i).getDisplayName());
                dialogIds.add(availableTtsLocales.get(i).getISO3Language());
            }
            String[] items = new String[dialogItems.size()];
            dialogItems.toArray(items);

            builder.title(res.getString(R.string.select_locale_title))
                    .items(items)
                    .itemsCallback((materialDialog, view, which, charSequence) -> {
                        String locale = dialogIds.get(which);
                        Timber.d("ReadText.selectTts() user chose locale '%s'", locale);
                        if (!locale.equals(mReadText.NO_TTS)) {
                            speak(mTextToSpeak, locale, TextToSpeech.QUEUE_FLUSH);
                        }
                        MetaDB.storeLanguage(mReviewer.get(), mDid, mOrd, mQuestionAnswer, locale);
                    });
        }
        // Show the dialog after short delay so that user gets a chance to preview the card
        showDialogAfterDelay(builder, 500);
    }

    /**
     * Read a card side using a TTS service.
     *
     * @param cardSide         Card side to be read; SoundSide.SOUNDS_QUESTION or SoundSide.SOUNDS_ANSWER.
     * @param cardSideContents Contents of the card side to be read, in HTML format. If it contains
     *                         any &lt;tts service="android"&gt; elements, only their contents is
     *                         read; otherwise, all text is read. See TtsParser for more details.
     * @param did              Index of the deck containing the card.
     * @param ord              The card template ordinal.
     */
    public void readCardSide(Sound.SoundSide cardSide, String cardSideContents, long did, int ord, String clozeReplacement) {
        boolean isFirstText = true;
        for (TtsParser.LocalisedText textToRead : TtsParser.getTextsToRead(cardSideContents, clozeReplacement)) {
            if (!textToRead.getText().isEmpty()) {
                textToSpeech(textToRead.getText(), did, ord, cardSide,
                        textToRead.getLocaleCode(),
                        isFirstText ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD);
                isFirstText = false;
            }
        }
    }

    public void speak(String text, String loc, int queueMode) {
        int result = mTTS.setLanguage(LanguageUtils.localeFromStringIgnoringScriptAndExtensions(loc));
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            UIUtils.showThemedToast(mReviewer.get(), mReviewer.get().getString(R.string.no_tts_available_message)
                    + " (" + loc + ")", false);
            Timber.e("Error loading locale %s", loc);
        } else {
            if (mTTS.isSpeaking() && queueMode == TextToSpeech.QUEUE_FLUSH) {
                Timber.d("tts engine appears to be busy... clearing queue");
                stopTts();
                //sTextQueue.add(new String[] { text, loc });
            }
            Timber.d("tts text '%s' to be played for locale (%s)", text, loc);
            mTTS.speak(mTextToSpeak, queueMode, new Bundle(), "stringId");
        }
    }

    private void openTtsHelpUrl(Uri helpUrl) {
        AnkiActivity activity =  (AnkiActivity) mReviewer.get();
        activity.openUrl(helpUrl);
    }

    public void stopTts() {
        if (mTTS != null) {
            mTTS.stop();
        }
    }

    @SuppressWarnings("deprecation") //  #7111: new Handler()
    private void showDialogAfterDelay(MaterialDialog.Builder builder, int delayMillis) {
        final Handler handler = new Handler();
        handler.postDelayed(() -> {
            try {
                builder.build().show();
            } catch (WindowManager.BadTokenException e) {
                Timber.w(e,"Activity invalidated before TTS language dialog could display");
            }
        }, delayMillis);
    }

    public String getLanguage(long did, int ord, Sound.SoundSide qa) {
        return MetaDB.getLanguage(mReviewer.get(), did, ord, qa);
    }
}
