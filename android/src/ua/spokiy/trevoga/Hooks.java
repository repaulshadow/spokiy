package ua.spokiy.trevoga;

import android.content.Context;
import android.content.Intent;

/**
 * Відмінності збірки для Google Play (push замість постійної служби). У збірці із сайту Flavor.hooks == null,
 * і все працює як раніше; реалізація для Play — в android/play/src.
 */
interface Hooks {
    /** Увімкнути стеження: підписка на push області, рідке запасне опитування, перевірка зараз. */
    void start(Context c);
    /** Перевірити зараз (відкрили програму, змінили місце). */
    void checkNow(Context c);
    /** Вимкнути стеження. */
    void stop(Context c);
    /** Дія «Вимкнути звук» у сповіщенні. */
    Intent stopSoundIntent(Context c);
    /** Лише тестова збірка: імітація push (JSON зрізу області). */
    void debugPush(Context c, String json);
}
