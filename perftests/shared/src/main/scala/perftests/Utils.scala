package perftests

object Utils {

  def time(f: () => Unit, maxTime: Int = 10000): Int = {
    val start = System.currentTimeMillis()
    var count = 0
    while(System.currentTimeMillis() - start < maxTime){
      f()
      count += 1
    }
    count
  }

  def benchmark(fs: Seq[() => Unit], iterations: Int = 5, maxTime: Int = 10000): Seq[Seq[Int]] = {
    (1 to iterations).map(i => fs.map(f => time(f, maxTime)))
  }
}
