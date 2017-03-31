package org.nvotes.libmix

import ch.bfh.unicrypt.crypto.mixer.classes.ReEncryptionMixer
import ch.bfh.unicrypt.crypto.proofsystem.challengegenerator.classes.FiatShamirSigmaChallengeGenerator
import ch.bfh.unicrypt.crypto.proofsystem.challengegenerator.interfaces.ChallengeGenerator
import ch.bfh.unicrypt.crypto.proofsystem.challengegenerator.interfaces.SigmaChallengeGenerator
import ch.bfh.unicrypt.crypto.proofsystem.classes.EqualityPreimageProofSystem
import ch.bfh.unicrypt.crypto.proofsystem.classes.PermutationCommitmentProofSystem
import ch.bfh.unicrypt.crypto.proofsystem.classes.PlainPreimageProofSystem
import ch.bfh.unicrypt.crypto.proofsystem.classes.ReEncryptionShuffleProofSystem
import ch.bfh.unicrypt.crypto.schemes.commitment.classes.PermutationCommitmentScheme
import ch.bfh.unicrypt.math.algebra.general.abstracts.AbstractSet
import ch.bfh.unicrypt.crypto.schemes.encryption.classes.ElGamalEncryptionScheme
import ch.bfh.unicrypt.helper.math.Alphabet
import ch.bfh.unicrypt.math.algebra.concatenative.classes.StringElement
import ch.bfh.unicrypt.math.algebra.concatenative.classes.StringMonoid
import ch.bfh.unicrypt.math.algebra.general.classes.Pair
import ch.bfh.unicrypt.math.algebra.general.classes.PermutationElement
import ch.bfh.unicrypt.math.algebra.general.classes.Triple
import ch.bfh.unicrypt.math.algebra.general.classes.Tuple
import ch.bfh.unicrypt.math.algebra.general.interfaces.Element
import ch.bfh.unicrypt.math.algebra.multiplicative.classes.GStarModElement
import ch.bfh.unicrypt.math.function.classes.CompositeFunction
import ch.bfh.unicrypt.math.function.classes.GeneratorFunction
import ch.bfh.unicrypt.math.function.classes.InvertFunction
import ch.bfh.unicrypt.math.function.classes.MultiIdentityFunction
import ch.bfh.unicrypt.math.function.classes.ProductFunction
import ch.bfh.unicrypt.math.function.interfaces.Function

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import mpservice.MPBridgeS
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Functions needed for a keymaker trustee
 *
 * Creation of key shares and partial decryptions, along with necessary proofs and verification
 */
trait KeyMaker extends ProofSettings {

  val logger = LoggerFactory.getLogger(getClass)

  def createShare(proverId: String, Csettings: CryptoSettings) = {

    val elGamal = ElGamalEncryptionScheme.getInstance(Csettings.generator)

    val kpg = elGamal.getKeyPairGenerator()
    val keyPair = kpg.generateKeyPair()
    val privateKey = keyPair.getFirst()
    val publicKey = keyPair.getSecond()

    val function = kpg.getPublicKeyGenerationFunction()
    val otherInput: StringElement = StringMonoid.getInstance(Alphabet.UNICODE_BMP).getElement(proverId)

    val challengeGenerator: SigmaChallengeGenerator  = FiatShamirSigmaChallengeGenerator.getInstance(
      Csettings.group.getZModOrder(), otherInput, convertMethod, hashMethod, converter)

    val pg: PlainPreimageProofSystem = PlainPreimageProofSystem.getInstance(challengeGenerator, function)

    val proof: Triple = pg.generate(privateKey, publicKey)

    val sigmaProofDTO = SigmaProofDTO(pg.getCommitment(proof).convertToString(), pg.getChallenge(proof).convertToString(), pg.getResponse(proof).convertToString())

    // we return the share dto and the generated private key
    (EncryptionKeyShareDTO(sigmaProofDTO, publicKey.convertToBigInteger().toString), privateKey.convertToBigInteger().toString)
  }

  def partialDecrypt(votes: Seq[Tuple], privateKey: Element[_], proverId: String, Csettings: CryptoSettings) = {

    val encryptionGenerator = Csettings.generator

    val secretKey = Csettings.group.getZModOrder().getElementFrom(privateKey.convertToBigInteger)
    // logger.info(s"PartialDecrypt: keymaker using secretKey $secretKey")
    val decryptionKey = secretKey.invert()
    val publicKey = encryptionGenerator.selfApply(secretKey)

    val generators = votes.par.map { v =>
      val element = v.getFirst()
      // ask Rolf about this
      if(element.convertToString == "1") {
        logger.info("********** Crash incoming!")
      }

      GeneratorFunction.getInstance(element)
    }.seq

    val lists = MPBridgeS.ex(generators.map{ generator =>
      val partialDecryption = generator.apply(decryptionKey).asInstanceOf[GStarModElement]
      (partialDecryption, generator)
    }, "2").unzip

    val proofDTO = createProof(proverId, secretKey, publicKey, lists._1, lists._2, Csettings)

    PartialDecryptionDTO(lists._1.par.map(_.convertToString).seq, proofDTO)
  }

  private def createProof(proverId: String, secretKey: Element[_], publicKey: Element[_],
    partialDecryptions: Seq[Element[_]], generatorFunctions: Seq[Function], Csettings: CryptoSettings) = {

    val encryptionGenerator = Csettings.generator

    // Create proof functions
    val f1: Function = GeneratorFunction.getInstance(encryptionGenerator)

    val f2: Function = CompositeFunction.getInstance(
        InvertFunction.getInstance(Csettings.group.getZModOrder()),
        MultiIdentityFunction.getInstance(Csettings.group.getZModOrder(), generatorFunctions.length),
        ProductFunction.getInstance(generatorFunctions :_*))

    val privateInput = secretKey
    val publicInput: Pair = Pair.getInstance(publicKey, Tuple.getInstance(partialDecryptions:_*))
    val otherInput = StringMonoid.getInstance(Alphabet.UNICODE_BMP).getElement(proverId)

    val challengeGenerator: SigmaChallengeGenerator = FiatShamirSigmaChallengeGenerator.getInstance(
        Csettings.group.getZModOrder(), otherInput, convertMethod, hashMethod, converter)


    val proofSystem: EqualityPreimageProofSystem = EqualityPreimageProofSystem.getInstance(challengeGenerator, f1, f2)

    val proof: Triple = proofSystem.generate(privateInput, publicInput)

    SigmaProofDTO(proofSystem.getCommitment(proof).convertToString(), proofSystem.getChallenge(proof).convertToString(), proofSystem.getResponse(proof).convertToString())
  }
}

/**
 * Functions needed for a mixer trustee
 *
 * Creation of shuffles and proofs (Terelius Wikstrom according to Locher-Haenni pdf)
 */
trait Mixer extends ProofSettings {

  val logger = LoggerFactory.getLogger(getClass)


  // corresponds to the offline phase of the proof of shuffle (permutation for known number of votes)
  def preShuffle(voteCount: Int, publicKey: Element[_], Csettings: CryptoSettings, proverId: String) = {

    val elGamal = ElGamalEncryptionScheme.getInstance(Csettings.generator)

    val mixer: ReEncryptionMixer = ReEncryptionMixer.getInstance(elGamal, publicKey, voteCount)
    val psi: PermutationElement = mixer.getPermutationGroup().getRandomElement()

    val pcs: PermutationCommitmentScheme = PermutationCommitmentScheme.getInstance(Csettings.group, voteCount)
    val permutationCommitmentRandomizations: Tuple = pcs.getRandomizationSpace().getRandomElement()

    val permutationCommitment: Tuple = pcs.commit(psi, permutationCommitmentRandomizations)

    logger.info("Mixer: generators..")

    // sigma challenge generator
    val otherInput: StringElement = StringMonoid.getInstance(Alphabet.UNICODE_BMP).getElement(proverId)
    val challengeGenerator: SigmaChallengeGenerator = FiatShamirSigmaChallengeGenerator.getInstance(
        Csettings.group.getZModOrder(), otherInput, convertMethod, hashMethod, converter)

    // e-values challenge generator
    val ecg: ChallengeGenerator = PermutationCommitmentProofSystem.createNonInteractiveEValuesGenerator(
        Csettings.group.getZModOrder(), voteCount)

    logger.info("Mixer: permutation proof..")

    val pcps: PermutationCommitmentProofSystem = PermutationCommitmentProofSystem.getInstance(challengeGenerator, ecg,
        Csettings.group, voteCount)

    val privateInputPermutation: Pair = Pair.getInstance(psi, permutationCommitmentRandomizations)
    val publicInputPermutation = permutationCommitment

    logger.info("Mixer: permutation proof, generating..")

    val permutationProof = pcps.generate(privateInputPermutation, publicInputPermutation)

    val bridgingCommitments = pcps.getBridingCommitment(permutationProof).asInstanceOf[Tuple]
    val eValues = pcps.getEValues(permutationProof).asInstanceOf[Tuple]
    val permutationProofDTO = PermutationProofDTO(pcps.getCommitment(permutationProof).convertToString(),
      pcps.getChallenge(permutationProof).convertToString(),
      pcps.getResponse(permutationProof).convertToString(),
      bridgingCommitments.asScala.par.map(x => x.convertToString).seq.toSeq,
      eValues.asScala.par.map(x => x.convertToString).seq.toSeq)

    val preShuffleData = PreShuffleData(psi, permutationCommitmentRandomizations)

    (permutationProofDTO, preShuffleData)
  }

  // online phase of the proof of shuffle, requires preshuffle data from offline phase
  def shuffle(ciphertexts: Tuple, pre: PreShuffleData, pdto: PermutationProofDTO,
    publicKey: Element[_], cSettings: CryptoSettings, proverId: String) = {

    logger.info("Mixer: shuffle..")
    val elGamal = ElGamalEncryptionScheme.getInstance(cSettings.generator)
    val mixer: ReEncryptionMixer = ReEncryptionMixer.getInstance(elGamal, publicKey, ciphertexts.getArity)
    val rs: Tuple = mixer.generateRandomizations()
    // in case we need to serialize perm data as strings
    // val psi: PermutationElement = mixer.getPermutationGroup().getElementFrom(pre.permutation)
    val psi: PermutationElement = pre.permutation

    // shuffle
    val shuffledVs: Tuple = mixer.shuffle(ciphertexts, psi, rs)

    logger.info("Mixer: shuffle proof..")

    val otherInput: StringElement = StringMonoid.getInstance(Alphabet.UNICODE_BMP).getElement(proverId)
    val challengeGenerator: SigmaChallengeGenerator = FiatShamirSigmaChallengeGenerator.getInstance(
        cSettings.group.getZModOrder(), otherInput, convertMethod, hashMethod, converter)

    val ecg: ChallengeGenerator = PermutationCommitmentProofSystem.createNonInteractiveEValuesGenerator(
        cSettings.group.getZModOrder(), ciphertexts.getArity)

    val spg: ReEncryptionShuffleProofSystem = ReEncryptionShuffleProofSystem.getInstance(challengeGenerator, ecg, ciphertexts.getArity(), elGamal, publicKey)

    val pcs: PermutationCommitmentScheme = PermutationCommitmentScheme.getInstance(cSettings.group, ciphertexts.getArity)

    // in case we need to serialize perm data as strings
    // val permutationCommitmentRandomizations: Tuple = Util.fromString(pcs.getRandomizationSpace(), pre.randomizations).asInstanceOf[Tuple]
    val permutationCommitmentRandomizations: Tuple = pre.randomizations

    val permutationCommitment: Tuple = pcs.commit(psi, permutationCommitmentRandomizations)

    val privateInputShuffle: Tuple = Tuple.getInstance(psi, permutationCommitmentRandomizations, rs)
    val publicInputShuffle: Tuple = Tuple.getInstance(permutationCommitment, ciphertexts, shuffledVs)

    logger.info("Mixer: shuffle proof, generating..")

    // shuffle proof
    val mixProof: Tuple = spg.generate(privateInputShuffle, publicInputShuffle)
    val eValues2: Tuple = spg.getEValues(mixProof).asInstanceOf[Tuple]

    logger.info(s"Mixer: evalues2 size: ${eValues2.getArity}")
    // FIXME remove trace (conversion bug code)
    // val commitment = spg.getCommitment(mixProof).convertToString
    // logger.info(s"*** commitment $commitment")
    // spg.getCommitmentSpace.asInstanceOf[AbstractSet[_,_]].getElementFrom(commitment)

    // FIXME whether or not using parallel collection on eValues2.map here is good
    val mixProofDTO = MixProofDTO(spg.getCommitment(mixProof).convertToString,
      spg.getChallenge(mixProof).convertToString,
      spg.getResponse(mixProof).convertToString,
      eValues2.asScala.map(x => x.convertToString).toSeq)


    val shuffleProofDTO = ShuffleProofDTO(mixProofDTO, pdto, permutationCommitment.convertToString)

    val votesString: Seq[String] = Util.stringsFromTuple(shuffledVs)

    ShuffleResultDTO(shuffleProofDTO, votesString)
  }

  // shuffle with both offline and online phase
  def shuffle(ciphertexts: Tuple, publicKey: Element[_], Csettings: CryptoSettings, proverId: String) = {

    val elGamal = ElGamalEncryptionScheme.getInstance(Csettings.generator)

    val mixer: ReEncryptionMixer = ReEncryptionMixer.getInstance(elGamal, publicKey, ciphertexts.getArity())
    val psi: PermutationElement = mixer.getPermutationGroup().getRandomElement()

    val pcs: PermutationCommitmentScheme = PermutationCommitmentScheme.getInstance(Csettings.group, ciphertexts.getArity())
    val permutationCommitmentRandomizations: Tuple = pcs.getRandomizationSpace().getRandomElement()

    val permutationCommitment: Tuple = pcs.commit(psi, permutationCommitmentRandomizations)

    logger.info("Mixer: generators..")

    // sigma challenge generator
    val otherInput: StringElement = StringMonoid.getInstance(Alphabet.UNICODE_BMP).getElement(proverId)
    val challengeGenerator: SigmaChallengeGenerator = FiatShamirSigmaChallengeGenerator.getInstance(
        Csettings.group.getZModOrder(), otherInput, convertMethod, hashMethod, converter)

    // e-values challenge generator
    val ecg: ChallengeGenerator = PermutationCommitmentProofSystem.createNonInteractiveEValuesGenerator(
        Csettings.group.getZModOrder(), ciphertexts.getArity())

    val pcps: PermutationCommitmentProofSystem = PermutationCommitmentProofSystem.getInstance(challengeGenerator, ecg,
        Csettings.group, ciphertexts.getArity())

    val privateInputPermutation: Pair = Pair.getInstance(psi, permutationCommitmentRandomizations)
    val publicInputPermutation = permutationCommitment

    logger.info("Mixer: permutation proof, generating..")

    val permutationProofFuture = Future {
      pcps.generate(privateInputPermutation, publicInputPermutation)
    }.map { permutationProof =>

      val bridgingCommitments = pcps.getBridingCommitment(permutationProof).asInstanceOf[Tuple].asScala.toList
      val eValues = pcps.getEValues(permutationProof).asInstanceOf[Tuple]
      val permutationProofDTO = PermutationProofDTO(pcps.getCommitment(permutationProof).convertToString(),
        pcps.getChallenge(permutationProof).convertToString(),
        pcps.getResponse(permutationProof).convertToString(),
        bridgingCommitments.par.map(x => x.convertToString).seq.toSeq,
        eValues.asScala.par.map(x => x.convertToString).seq.toSeq)

      permutationProofDTO
    }

    logger.info("Mixer: randomizations..")

    val rs: Tuple = mixer.generateRandomizations()

    logger.info("Mixer: shuffle..")

    // shuffle
    val shuffledVs: Tuple = mixer.shuffle(ciphertexts, psi, rs)

    logger.info("Mixer: shuffle proof..")

    // shuffle proof system
    val spg: ReEncryptionShuffleProofSystem = ReEncryptionShuffleProofSystem.getInstance(challengeGenerator, ecg, ciphertexts.getArity(), elGamal, publicKey)

    val privateInputShuffle: Tuple = Tuple.getInstance(psi, permutationCommitmentRandomizations, rs)
    val publicInputShuffle: Tuple = Tuple.getInstance(permutationCommitment, ciphertexts, shuffledVs)

    logger.info("Mixer: shuffle proof, generating..")

    // shuffle proof
    val mixProof: Tuple = spg.generate(privateInputShuffle, publicInputShuffle)
    val eValues2 = spg.getEValues(mixProof).asInstanceOf[Tuple]

    // FIXME conversion bug code
    // val commitment = spg.getCommitment(mixProof).convertToString
    // logger.info(s"*** commitment $commitment")
    // spg.getCommitmentSpace.asInstanceOf[AbstractSet[_,_]].getElementFrom(commitment)

    // FIXME whether or not using parallel collection on eValues2.map here is good
    val mixProofDTO = MixProofDTO(spg.getCommitment(mixProof).convertToString(),
      spg.getChallenge(mixProof).convertToString(),
      spg.getResponse(mixProof).convertToString(),
      eValues2.asScala.map(x => x.convertToString).toSeq)

    val permutationProofDTO = Await.result(permutationProofFuture, Duration.Inf)

    val shuffleProofDTO = ShuffleProofDTO(mixProofDTO, permutationProofDTO, permutationCommitment.convertToString)

    val votesString: Seq[String] = shuffledVs.asScala.par.map( x => x.convertToString ).seq.toList

    ShuffleResultDTO(shuffleProofDTO, votesString)
  }
}