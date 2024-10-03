package si.ogrodje.tttm.v2

import si.ogrodje.tttm.v2.apps.PlayApp.NumberOfGames
import zio.Console.printLine
import zio.{Scope, ZIO}
import zio.http.{Client, URL}
import zio.stream.ZStream
import zio.ZIO.logInfo
import zio.json.*

@jsonHintNames(SnakeCase)
final case class MatchResult(
  played: Long = 0,
  won: Long = 0,
  lost: Long = 0,
  tide: Long = 0
)
object MatchResult:
  val empty: MatchResult                                 = apply()
  given matchResultJsonEncoder: JsonEncoder[MatchResult] = DeriveJsonEncoder.gen[MatchResult]

object Match:
  private type NumberOfGames = Long
  private type MatchResults  = Map[PlayerServerID, MatchResult]

  private def updateResults(
    acc: MatchResults,
    serverA: PlayerServerID,
    serverB: PlayerServerID,
    gameplayResult: GameplayResult
  ): MatchResults =
    val maybeWinner          = gameplayResult.maybeWinner
    val (resultsA, resultsB) = acc(serverA) -> acc(serverB)

    acc ++ Map(
      serverA -> resultsA.copy(
        played = resultsA.played + 1,
        won = maybeWinner
          .flatMap(s => Option.when(s == serverA)(resultsA.won + 1))
          .getOrElse(resultsA.won),
        lost = maybeWinner
          .flatMap(s => Option.when(s == serverB)(resultsA.lost + 1))
          .getOrElse(resultsA.lost),
        tide = maybeWinner.fold(resultsA.tide + 1)(_ => resultsA.tide)
      ),
      serverB -> resultsB.copy(
        played = resultsB.played + 1,
        won = maybeWinner
          .flatMap(s => Option.when(s == serverB)(resultsB.won + 1))
          .getOrElse(resultsB.won),
        lost = maybeWinner
          .flatMap(s => Option.when(s == serverA)(resultsB.lost + 1))
          .getOrElse(resultsB.lost),
        tide = maybeWinner.fold(resultsB.tide + 1)(_ => resultsB.tide)
      )
    )

  def playGames(
    serverAUrl: URL,
    serverBUrl: URL,
    numberOfGames: NumberOfGames,
    concurrentProcesses: Int = 4,
    size: Size = Size.default,
    maybeGameplayReporter: Option[GameplayReporter] = None
  ): ZIO[Scope & Client, Throwable, Map[PlayerServerID, MatchResult]] =
    val servers @ (serverA, serverB) =
      ExternalPlayerServer.fromURL(serverAUrl) -> ExternalPlayerServer.fromURL(serverBUrl)

    ZStream
      .range(0, numberOfGames.toInt)
      .zipWithIndex
      .map {
        case (n, i) if i % 2 == 0 => n -> (serverA -> serverB)
        case (n, _) => n -> (serverB -> serverA)
      }
      .mapZIOParUnordered(concurrentProcesses) { case (n, (serverA, serverB)) =>
        Gameplay
          .make(serverA, serverB, size, maybeGameplayReporter = maybeGameplayReporter)
          .play
          .tap(r => logInfo(s"Completed game n. ${n}"))
      }
      .runFold(
        Map[PlayerServerID, MatchResult](
          serverA.id -> MatchResult.empty,
          serverB.id -> MatchResult.empty
        )
      ) { case (acc, (result, gameplayResult)) =>
        val gr = gameplayResult.toJson
        println(s"\nGR:\n${gr}")

        updateResults(acc, serverA.id, serverB.id, gameplayResult)
      }
