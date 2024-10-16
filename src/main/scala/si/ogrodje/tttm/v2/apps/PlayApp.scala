package si.ogrodje.tttm.v2.apps

import si.ogrodje.tttm.v2.{ExternalPlayerServer, Gameplay, PlayerServer}
import zio.ZIO.logInfo
import zio.cli.*
import zio.http.{Client, URL}
import zio.logging.backend.SLF4J
import zio.*
import zio.stream.{Stream, ZStream}
import zio.Console.printLine

import java.net.MalformedURLException

type RawURL = String
object RawURL:
  def parse(raw: RawURL): IO[MalformedURLException, URL] = ZIO.fromEither(URL.decode(raw))

object PlayApp extends ZIOCliDefault:
  override val bootstrap: ZLayer[ZIOAppArgs, Nothing, Any] =
    Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  private type Size          = BigInt
  private type ShouldScore   = Boolean
  private type NumberOfGames = BigInt
  private val score: Options[ShouldScore]           = Options.boolean("score").alias("s")
  private val size: Options[Size]                   = Options.integer("size").withDefault(BigInt(3))
  private val numberOfGames: Options[NumberOfGames] = Options.integer("games").alias("g").withDefault(BigInt(1))
  private val help: HelpDoc                         = HelpDoc.p("Plays a game between two servers.")

  private val (serverA: Args[RawURL], serverB: Args[RawURL]) =
    Args.text("server-a") -> Args.text("server-b")

  private val command: Command[((ShouldScore, Size, NumberOfGames), (RawURL, RawURL))] =
    Command("play", score ++ size ++ numberOfGames, serverA ++ serverB).withHelp(help)

  val cliApp = CliApp.make(
    name = "PlayApp",
    version = "0.0.1",
    summary = HelpDoc.Span.empty,
    command = command
  ) { case ((shouldScore, size, numberOfGames), _ @(serverA, serverB)) =>
    RawURL.parse(serverA).zipPar(RawURL.parse(serverB)).flatMap(play(shouldScore, numberOfGames))
  }

  private def play(
    shouldScore: ShouldScore = false,
    numberOfGames: BigInt = BigInt(1),
    size: BigInt = 3
  )(serverAUrl: URL, serverBUrl: URL) = for
    _       <- logInfo(s"Server A: $serverAUrl, server B: $serverBUrl, size: $size, should score: $shouldScore")
    serverA  = ExternalPlayerServer.fromURL(serverAUrl)
    serverB  = ExternalPlayerServer.fromURL(serverBUrl)
    gameplay = Gameplay.make(serverA, serverB)
    _       <- gameplay.play.provide(Client.default.and(Scope.default))
    out     <- playGames(numberOfGames)(serverAUrl, serverBUrl).provide(Client.default.and(Scope.default))
    _       <- printLine(out)
  yield ()

  final case class PlayerResults(
    playedGames: Int = 0,
    gamesWon: Int = 0,
    gamesLost: Int = 0
  )
  object PlayerResults:
    val empty: PlayerResults = PlayerResults(0, 0, 0)

  private def playGames(numberOfGames: NumberOfGames)(serverAUrl: URL, serverBUrl: URL) =
    val concurrent                   = 4
    val servers @ (serverA, serverB) =
      ExternalPlayerServer.fromURL(serverAUrl) -> ExternalPlayerServer.fromURL(serverBUrl)

    ZStream
      .range(0, numberOfGames.toInt)
      .zipWithIndex
      .map {
        case (n, i) if i % 2 == 0 => n -> (serverA -> serverB)
        case (n, _) => n -> (serverB -> serverA)
      }
      .mapZIOParUnordered(concurrent) { n =>
        Gameplay.make(serverA, serverB).play
      }
      .runFold(Map.empty[PlayerServer, PlayerResults]) { case (acc, (result, maybeWinner)) =>
        maybeWinner.fold(
          // No winner
          acc.updatedWith(serverA) {
            case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
            case None    => Some(PlayerResults.empty.copy(playedGames = 1))
          } ++
            acc.updatedWith(serverB) {
              case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
              case None    => Some(PlayerResults.empty.copy(playedGames = 1))
            }
        ) {
          case winner if winner == serverA =>
            acc.updatedWith(serverA) {
              case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
              case None    => Some(PlayerResults.empty.copy(playedGames = 1))
            } ++
              acc.updatedWith(serverB) {
                case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
                case None    => Some(PlayerResults.empty.copy(playedGames = 1))
              }
          case winner if winner == serverB =>
            println("won by server B")
            acc.updatedWith(serverA) {
              case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
              case None    => Some(PlayerResults.empty.copy(playedGames = 1))
            } ++
              acc.updatedWith(serverB) {
                case Some(v) => Some(v.copy(playedGames = v.playedGames + 1))
                case None    => Some(PlayerResults.empty.copy(playedGames = 1))
              }
          case _                           =>
            println("what?")
            acc
        }
      }
