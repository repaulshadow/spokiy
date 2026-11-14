package ua.spokiy.trevoga;

/** Збірка для Google Play: тривоги приходять push-повідомленнями (FCM), постійної служби й власного оновлення немає. */
final class Flavor {
    static final boolean PLAY = true;
    static final Hooks hooks = new PlayHooks();
}
