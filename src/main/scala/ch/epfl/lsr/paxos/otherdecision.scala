package ch.epfl.lsr.paxos

import ch.epfl.lsr.distal._
import ch.epfl.lsr.protocol.ProtocolLocation
import collection.Set

import java.util.concurrent.TimeUnit._

/* here we use a different decision rule:
 * - all replicas decide when receiving the Phase2b message (which is sent to everyone)
 */


/* Section 3.1:  in the Synod protocol, the president does not choose the decree or the quorum until step 3. */
// so we implement the first two steps from section 2.3
/*
 *
 * (A NextBallot(b) message is ignored if b ≤ nextBal[q].)
 */
abstract class FasterLegislator(val ID :LegislatorName, val ledger :Ledger, president : => LegislatorName) extends DSLProtocol { 
  import ledger._

  val MAJORITY = ALL.size/2 + 1

  private val toPropose = collection.mutable.Queue[DecreeValue]()
  private var currentBallot :BallotNr = -1
  def IamPresident = president == ID

  def decided(d :Decree)

  /*
   * starting a ballot:
   * 
   * p.12: 
   * (1) Priest $p$ chooses a new ballot number $b$ greater than last $Tried[p]$,sets $lastTried[p]$ to $b$,
   * and sends a $NextBallot(b)$ message to some set of priests.
   * 
   * p.15: 
   * if a newly elected president $p$ has all decrees with numbers less 
   * than or equal to $n$ written in his ledger, then he sends a 
   * $NextBallot(b, n)$ message that serves as a $NextBallot(b)$ 
   * message in all instances of the Synod protocol for decree 
   * numbers larger than $n$.
   */
  def startBallot() { 
    val b = lastTried + 1
    lastTried = b
    //println("SENDING "+NextBallot(b, haveAllWithLessThan))
  | SEND NextBallot(b, haveAllWithLessThan) TO ALL
  }

  /*
   * p.12:
   * (2) Upon receipt of a $NextBallot(b)$ message from $p$ with $b > nextBal[q]$,
   * priest q sets $nextBal[q]$ to $b$ and sends a $LastVote(b, v)$ message to $p$,
   * where $v$ equals $prevVote[q]$.
   *
   * p.15:
   *  In his response to this message, legislator $q$ informs p of all 
   * decrees numbered greater than $n$ that already appear in $q$'s ledger 
   * (in addition to sending the usual LastVote information for decrees 
   * not in his ledger), and he asks $p$ to send him any decrees numbered 
   * $n$ or less that are not in his ledger.
   */
  UPON RECEIVING NextBallot WITH { _.b > nextBal } DO { 
    msg =>
      //println("GOT NextBallot "+msg.b)
      nextBal = msg.b
    | SEND LastVote(msg.b,
		    msg.n,
		    prevVote, 	    
		    alreadyInLedgerGreaterThan(msg.n), 
		    Map(ID -> missingUpTo(msg.n))) TO SENDER

    | DISCARD msg
  }

  UPON RECEIVING NextBallot WITH { _.b <= nextBal } DO { 
    msg =>
      //println("some NextBallot "+msg.b)
      | DISCARD msg
  }

  /*
   * p.15:
   * When the new president has received a reply from every member of a majority
   * set, he is ready to perform step 3 for every instance of the Synod protocol.
   * For some finite number of instances (decree numbers), the choice of decree
   * in step 3 will be determined by B3. The president immediately performs step
   * 3 for each of those instances to try passing these decrees.
   *
   */
  UPON RECEIVING LastVote WITH { _.b == lastTried } TIMES MAJORITY DO { 
    msgs =>
      //println("HAVE lastvotes")

      val n = msgs.head.n
      // find which ones have a known outcome (were decided before): 
      val knownOutcomes :Set[Decree] = msgs.map(_.knownOutcome).reduce(_ ++ _)
      note(knownOutcomes)

      // find which ones someone is missing (less than n)
      val missing :Map[LegislatorName,Set[DecreeNr]] = msgs.map(_.askFor).reduce(_ ++ _)
      for{ 
	legislator <- missing.keySet
	dnr <- missing(legislator)
	d = getDecision(dnr)
	if d.nonEmpty // otherwise it was not less than n. should not happen.
      } | SEND Success(lastTried, getDecision(n).get) TO legislator

    
      // find which ones have previous votes
      val previousVotes :Set[Vote] = msgs.map(_.v).reduce(_ ++ _)
      val maxVotedDecree = previousVotes.toSeq.sortBy(_.dec.n).headOption.map{ _.dec.n }

      if(IamPresident && maxVotedDecree.nonEmpty) { 
	for{  
	  instance <- n to maxVotedDecree.get;
	  decree = getDecision(instance).orElse(findValue(instance, previousVotes)).getOrElse(BLANK(instance))
	} proposeDecree(decree)

	ledger.forwardNextDecreeNr(maxVotedDecree.get + 1)
      }

    | DISCARD msgs
      currentBallot = lastTried
      
      // propose those received before finishing the first phase
      if(IamPresident) { 
	for(value <- toPropose) 
	  proposeDecree(Decree(nextDecreeNr, value))
      }
  }    

  def proposeDecree(decree :Decree) { 
    // println("proposing "+decree.n)
    if(IamPresident && currentBallot == lastTried) { 
      | SEND BeginBallot(lastTried, decree) TO ALL
    } 
  }
  
  def findValue(instance :DecreeNr, votes :Set[Vote]) = { 
    val votesForInstance = votes.filter(_.dec.n == instance) 
    
    if(votesForInstance.isEmpty) None
    else Some(votesForInstance.maxBy( _.bal ).dec)
  }
  
  UPON RECEIVING LastVote WITH { _.b <= currentBallot } DO { 
    msg =>
      | DISCARD msg
  }

  /*
   * (4) Upon receipt of a $BeginBallot(b, d)$ message with $b = nextBal[q]$, 
   * priest $q$ casts his vote in ballot number $b$, sets $prevVote[q]$ to 
   * this vote, and sends a $Voted(b, q)$ message to $p$. 
   * (A BeginBallot(b, d) message is ignored if $b\ne nextBal[q].)
   */
  UPON RECEIVING BeginBallot WITH { _.b == nextBal} DO { 
    msg =>
      if(SENDER != LOCATION) {  
	// remote
	val vote = Vote(msg.b, msg.d)
	prevVote += vote
	| FORWARD Voted(msg.b, msg.d) TO this 
	| SEND Voted(msg.b, msg.d) TO ALL
      }
    | DISCARD msg
  }

  UPON RECEIVING BeginBallot WITH { _.b != nextBal} DO { 
    msg => 
    | DISCARD msg
  }
  
   // (5) If $p$ has received a $Voted(b, q)$ message from every priest 
  // $q$ in $Q$ (the quorum for ballot number $b$), where $b = lastTried[p]$, 
  // then he writes $d$ (the decree of that ballot) in his ledger and sends 
  // a Success(d) message to every priest.
  UPON RECEIVING Voted WITH { _.b == nextBal } SAME { _.d.n } TIMES MAJORITY DO { 
    msgs =>
      val msg = msgs.head
      note(msg.d)
      decided(msg.d)
    
      prevVote -= Vote(msg.b, msg.d)

      assert(isDecided(msgs.head))

    | DISCARD msgs
  }
  
  def isDecided(voted :Voted) = { 
    alreadyDecided(voted.d.n)
  }

  UPON RECEIVING Voted WITH { _.b == nextBal } WITH isDecided DO { 
    msg => 
      | DISCARD msg
  }

  UPON RECEIVING Voted WITH { _.b != nextBal } DO { 
    msg => 
      | DISCARD msg
  }

  // (6) Upon receiving a $Success(d)$ message, a priest enters decree $d$ in his ledger.
  UPON RECEIVING Success DO {  // mostly unused
    msg => 
      prevVote -= Vote(msg.b, msg.d)
      if(!alreadyDecided(msg.d.n)) { // the leader has written it already.
	note(msg.d)
	decided(msg.d)
      }
    | DISCARD msg
  }

  // END SYNOD

  def proposeRequest(value :DecreeValue) { 
      if(IamPresident && currentBallot == lastTried) { 
	proposeDecree(Decree(nextDecreeNr, value))
      } else if(IamPresident) { 
	toPropose += value
      } else { 
	println("TODO: Request at non-leader")
	// TODO: tell client to look somewhere else
      }
  }
  
  // 
  UPON RECEIVING Elected DO { 
    msg => 
      //println("Elected!?")
      if(IamPresident && currentBallot == lastTried)
	startBallot
    | DISCARD msg
  }


  UPON RECEIVING START DO { 
    msg =>
      | AFTER 5(SECONDS) DO { 
	| SEND Elected() TO ALL
      }
  }
  }
