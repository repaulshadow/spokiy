using System; using System.Threading;
class P {
  static int fired;
  static void Make() { var t = new Timer(delegate { Interlocked.Increment(ref fired); }, null, 1500, Timeout.Infinite); }
  static void Main() {
    Make();
    for (int i = 0; i < 5; i++) { GC.Collect(); GC.WaitForPendingFinalizers(); Thread.Sleep(100); }
    Thread.Sleep(2500);
    Console.WriteLine(fired == 0 ? "COLLECTED: timer did not fire" : "FIRED");
  }
}
